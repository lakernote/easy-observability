# 示例应用

- `easy-observability-example-spring-boot-3`：Spring Boot 3，默认地址 `http://localhost:8083/`
- `easy-observability-example-spring-boot-4`：Spring Boot 4，默认地址 `http://localhost:8084/`

主页提供正常请求、慢请求、错误请求和嵌套 Trace 操作。嵌套调用标为模拟，不会连接真实 Redis、数据库或远程服务。示例将监控 API 的匿名访问限制放宽，仅适用于本地体验。

在仓库根目录运行：

```bash
mvn -pl examples/easy-observability-example-spring-boot-3 -am package
java -jar examples/easy-observability-example-spring-boot-3/target/easy-observability-example-spring-boot-3-0.1.0-alpha.0.jar

mvn -pl examples/easy-observability-example-spring-boot-4 -am package
java -jar examples/easy-observability-example-spring-boot-4/target/easy-observability-example-spring-boot-4-0.1.0-alpha.0.jar
```
