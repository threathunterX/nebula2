#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""隐私检查器自身的测试。

这个脚本是 CI 门禁,它自己出错没有别的东西会发现 —— 门禁静默失效比没有门禁更糟,
因为它会让人相信已经检查过了。

**每条断言都成对**:该抓的要抓到,不该抓的不能误报。只测其中一个方向的话,
把判定函数改成恒真或恒假都能让测试通过。

注意本文件<b>刻意不写出任何完整的真实形态字面量</b>(伪装邮箱、公网 IP)——
写出来会被检查器自己抓到。全部用拼接构造。

不给测试目录开例外,是因为「整目录排除」正是这类门禁最常见的失效方式:
排除一次之后,后来真的混进去的东西也不会被发现。宁可写得别扭一点。
"""
import importlib.util
import pathlib
import unittest

_SRC = pathlib.Path(__file__).resolve().parent.parent / "check_no_pii.py"
_spec = importlib.util.spec_from_file_location("check_no_pii", _SRC)
chk = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(chk)


def _luhn_complete(prefix):
    for d in "0123456789":
        if chk._luhn_ok(prefix + d):
            return prefix + d
    raise AssertionError("构造不出通过 Luhn 的号码")


def _id_complete(first17):
    total = sum(int(first17[i]) * chk._ID_WEIGHTS[i] for i in range(17))
    return first17 + chk._ID_CHECK[total % 11]


class TestBankCard(unittest.TestCase):
    def test_valid_luhn_is_flagged(self):
        self.assertTrue(chk._luhn_ok(_luhn_complete("622202" + "0" * 11)))

    def test_random_digits_are_not_flagged(self):
        """哈希片段、拼接的时间戳形态上像卡号,但过不了 Luhn。

        没有这一层时误报面很宽,而脚本给出的补救方式是往允许列表加例外 ——
        长期看会侵蚀检查的严格性。"""
        ok = _luhn_complete("622202" + "0" * 11)
        broken = ok[:-1] + str((int(ok[-1]) + 1) % 10)
        self.assertFalse(chk._luhn_ok(broken))


class TestChinaID(unittest.TestCase):
    def test_valid_checksum_is_flagged(self):
        self.assertTrue(chk._id_checksum_ok(_id_complete("11010119900307" + "123")))

    def test_wrong_checksum_is_not_flagged(self):
        good = _id_complete("11010119900307" + "123")
        bad = good[:-1] + ("0" if good[-1] != "0" else "1")
        self.assertFalse(chk._id_checksum_ok(bad))

    def test_wrong_length_is_not_flagged(self):
        self.assertFalse(chk._id_checksum_ok("11010119900307123"))


class TestEmailDomain(unittest.TestCase):
    """域名必须精确比对。

    早先用的是子串匹配,把允许域名嵌进自己的域名里就能放行 —— 想混一个真实邮箱
    进来,只要把允许的域名当作自己域名的一部分即可。"""

    ALLOWED = "example.com"

    def test_exact_and_subdomain_allowed(self):
        self.assertTrue(chk._domain_allowed("a@" + self.ALLOWED))
        self.assertTrue(chk._domain_allowed("a@mail." + self.ALLOWED))

    def test_prefix_disguise_rejected(self):
        self.assertFalse(chk._domain_allowed("a@not" + self.ALLOWED))

    def test_suffix_disguise_rejected(self):
        self.assertFalse(chk._domain_allowed("a@" + self.ALLOWED + ".attacker.cn"))

    def test_unrelated_domain_rejected(self):
        self.assertFalse(chk._domain_allowed("a@" + "realcompany" + ".cn"))


class TestPublicIP(unittest.TestCase):
    def test_private_and_reserved_are_not_public(self):
        for ip in ("10.0.0.1", "127.0.0.1", "172.16.0.1", "0.0.0.0"):
            self.assertFalse(chk._is_public_ip(ip), ip)

    def test_multicast_whole_range(self):
        """早先只排除了字面量 224.,224.0.0.0/4 覆盖到 239。"""
        for ip in ("224.0.0.1", "231.1.1.1", "239.255.255.255"):
            self.assertFalse(chk._is_public_ip(ip), ip)

    def test_reserved_240_range(self):
        self.assertFalse(chk._is_public_ip("240.0.0.1"))

    def test_real_public_ip_is_flagged(self):
        """这条是关键的反向断言:上面几条都在放宽,如果放宽过头,
        真实公网 IP 也会被漏掉 —— 那这个检查就完全没用了。"""
        self.assertTrue(chk._is_public_ip("8.8" + ".4.4"))
        self.assertTrue(chk._is_public_ip("223.255" + ".255.255"))


class TestVersionHeuristic(unittest.TestCase):
    @staticmethod
    def _first_ip_match(line):
        pat = next(p for name, p, _ in chk.CHECKS if name == "公网 IP 字面量")
        return pat.search(line)

    def test_user_agent_version_not_treated_as_ip(self):
        line = "Mozilla/5.0 Chrome/120.0.0.0 Safari/537.36"
        self.assertTrue(chk._looks_like_version(self._first_ip_match(line), line))

    def test_product_name_then_version(self):
        """「OpenResty 1.31.1.1」这类文档里的版本号。

        写 OpenResty 埋点文档时撞出来的:版本号被判成公网 IP。
        空格、斜杠、半角与全角括号都要认。"""
        for line in [
            "用官方镜像的真实 OpenResty 1.31.1.1 验证",
            "用官方镜像的真实 OpenResty(1.31.1.1)验证",
            "OpenResty (1.31.1.1)",
            "Redis 7.2.1.0 已发布",
            "nginx version: openresty/1.31.1.1",
        ]:
            m = self._first_ip_match(line)
            self.assertIsNotNone(m, line)
            self.assertTrue(chk._looks_like_version(m, line), line)

    def test_version_heuristic_does_not_swallow_real_ips(self):
        """反向断言:放宽不能宽到把真 IP 一起放过。

        软件名与 IP 之间隔了别的词时,那就是个 IP 而不是版本号 ——
        这正是「OpenResty 部署在 8.8.4.4 上」这类句子。"""
        for line in [
            "OpenResty 部署在 " + "8.8" + ".4.4 上",
            "客户端 IP 是 " + "223.255" + ".255.255",
            "上游地址 " + "1.1" + ".1.1",
        ]:
            m = self._first_ip_match(line)
            self.assertIsNotNone(m, line)
            self.assertFalse(chk._looks_like_version(m, line), line)


if __name__ == "__main__":
    unittest.main(verbosity=2)
