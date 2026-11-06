# 贡献指南

感谢你为 Meerkat 贡献代码、文档或问题反馈。请围绕当前可验证的监控能力提交改动，并说明行为变化和兼容性影响。

## 本地开发

需要 Java 17 和 Maven。运行全部验证：

```bash
mvn verify
```

`mvn verify` 包含覆盖率门禁：`meerkat-spring-boot-common` 的单元测试行覆盖率需不低于 80%、分支覆盖率不低于 62%，不达标会导致构建失败。阈值只随覆盖率提升而上调，不要为了让改动通过而下调。包含 E2E 的聚合报告位于 `meerkat-spring-boot-{3,4}-starter/target/site/jacoco-aggregate/index.html`，CI 在 JDK 21 上会把它作为 `coverage-report` 制品上传。

修改 Trace 采集路径时，请运行 [JMH 基准](benchmarks/README.md)，并更新 `docs/guide.md`（Overhead）与 `docs/guide.zh-CN.md`（性能开销）中的参考结果。修改文档时请同步中英文两个版本。

Pull Request 和 `main` 分支的推送会触发 CI，在 JDK 17、21、25 上运行同一套验证（每次都覆盖 Boot 3 和 Boot 4 模块），并用 `central-release` profile 检查 sources 和 Javadoc 打包。

## Maven Central 发布

发布的 Maven 坐标由根 POM、`meerkat-spring-boot-common`、两个 `*-autoconfigure` 模块和两个 `*-starter` 模块组成。三个实现模块和两个依赖汇总 starter 都发布 JAR、sources JAR 和 Javadoc JAR；starter JAR 不包含运行时代码，保留标准 JAR 坐标以便应用像其他 Spring Boot starter 一样直接依赖。`examples` 和 `benchmarks` 仅用于演示和性能测量，配置为跳过发布。

发布前先运行完整验证，再生成本地 Central bundle 检查实际内容。以下命令显式跳过上传，但仍会调用 GPG 签名；构建环境必须已配置可非交互使用的 GPG 私钥：

```bash
mvn clean verify
mvn -Pcentral-release -Dcentral.skipUpload=true clean deploy
```

检查 `target/central-publishing/central-bundle.zip` 中包含的坐标、POM、JAR、sources、Javadoc、签名和校验文件。若只需检查未签名的本地产物，可额外加 `-Dgpg.skip=true`；这种 bundle 不能上传到 Central。只有完成审核、签名配置可用并明确准备发布时，才运行不带 `-Dcentral.skipUpload=true` 的命令；当前配置会在校验成功后自动发布。Maven Central 已发布版本不可覆盖，应先确认版本号、依赖范围和最终 bundle 内容。

## Pull Request

请说明：

- 变更目的和影响范围。
- 配置项、指标、Trace 数据或 HTTP 接口是否有变化。
- 已运行的验证命令和结果。

请勿提交凭据、真实业务数据、数据库文件或未脱敏日志。安全问题请通过仓库的私密安全报告渠道提交，不要公开漏洞利用细节。
