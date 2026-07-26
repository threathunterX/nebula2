# 采集器 —— Go,零外部依赖,静态编译
FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY . .
# CGO_ENABLED=0:静态二进制,运行镜像里不需要 libc
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/collector ./cmd/nebula-collector

# distroless 而不是 alpine:采集器直连业务流量,是最暴露的一环。
# 镜像里没有 shell、没有包管理器,拿到 RCE 也没有现成的工具可用。
FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/collector /collector
USER nonroot:nonroot
ENTRYPOINT ["/collector"]
