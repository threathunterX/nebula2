# 种子资产导入。
#
# 全新部署起来后元数据库是空的:没有事件模型、没有变量、没有策略,控制面能登录
# 但什么也管不了,引擎拉到的 bundle 是空的。把首次导入做成编排的一部分,
# 「起来了」和「能用」才是同一件事。
#
# 幂等:全部 ON CONFLICT DO UPDATE,重复执行安全。
FROM python:3.14-slim
RUN pip install --no-cache-dir "psycopg[binary]"
WORKDIR /app
COPY tools/load_seeds_to_postgres.py /app/tools/
COPY seeds /app/seeds
ENV NEBULA_SEEDS_DIR=/app/seeds
CMD ["python", "tools/load_seeds_to_postgres.py"]
