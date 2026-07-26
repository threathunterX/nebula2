# 建表任务。
#
# schema 打进镜像而不是从宿主 bind mount:bind mount 让「能不能起来」取决于仓库
# 在宿主上的位置,以及 Docker Desktop / Colima 的文件共享配置 —— 这类失败表现为
# 目录为空,不是报错,查起来毫无线索。镜像里有什么是确定的。
FROM python:3.14-slim
RUN pip install --no-cache-dir "psycopg[binary]"
WORKDIR /schema
COPY . /schema
# 建表是幂等的(全部 CREATE ... IF NOT EXISTS),重复执行安全
CMD ["sh", "-c", "python apply_postgres.py && python apply_clickhouse.py"]
