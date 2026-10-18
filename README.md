# SudPanel

面向小游戏测试的本地工具，将状态上报检查、接口测试、Android 性能采样和结果分析放在同一界面，减少重复配置与工具切换。

本仓库用于个人作品展示和源码查阅，原创代码保留权利，暂不授予 MIT 或其他开源使用许可。第三方组件仍遵循各自许可，详见文末说明。

## 主要功能

- **状态上报检查**：支持游戏到 App、App 到游戏两种方向；按测试点过滤日志、检查字段与预期，支持人工触发和配置后的自动点击。
- **接口测试**：请求编辑、变量与环境、签名、响应体断言、响应提取、顺序执行、前后置脚本、JSON Schema 校验，以及历史结果对比。
- **性能测试**：通过配置的操作或接口编排测试流程，采集 PSS、CPU、线程等指标，展示跨轮趋势、逐秒曲线与阶段变化，辅助排查异常。
- **结果与日志**：日志关键词、正则与 Tag/PID 过滤；导出 HTML/PDF 分析报告、CSV 样本和执行证据。
- **本地数据管理**：保存配置与历史，支持数据导出、备份和接口配置导入。完整数据迁移代码保留，但“整包一键导入并自动重启”依赖原生安装包，在本次 JAR 发行中不可用。

## 快速开始

当前完整构建与回归验证支持 macOS。准备 Java 21、Maven 3.9、Node.js 22.22 或更新的兼容 LTS 版本，以及 npm、Python 3 和 OpenSSL。脚本执行隔离依赖 macOS 的 `sandbox-exec`；Linux/Windows 尚未完成适配。以下命令记录本项目的构建过程：

```bash
bash scripts/build.sh
java -jar target/sud-report-panel-1.12.7-public.1.jar
```

浏览器打开 **http://127.0.0.1:18890**。构建先生成前端资源，再执行后端测试并打包。构建不会复制本机数据库、Android SDK 或业务配置。

默认数据目录是 `~/.sudpanel-open/`，首次运行自动创建空的业务数据库；内置测试点和返回码说明来自公开 SDK 协议。可以通过环境变量指定独立目录和端口：

```bash
SUDPANEL_DATA_DIR="$PWD/.local-data" SUDPANEL_PORT=18891 \
  java -jar target/sud-report-panel-1.12.7-public.1.jar
```

默认禁用 ADB。只查看界面、配置接口用例无需手机；启用设备相关功能时，单独安装 Android SDK Platform Tools，并显式设置路径：

```bash
SUDPANEL_ADB_PATH="/path/to/platform-tools/adb" \
  java -jar target/sud-report-panel-1.12.7-public.1.jar
```

启用后，界面会定期查询已连接设备。执行测试前需自行配置被测包名、控件定位、账号、地址及鉴权；源码不提供可访问真实业务的配置。控件映射格式见 `config/resource-ids.properties.example`，默认实际文件位置为 `~/.sudpanel-open/config/resource-ids.properties`。

PDF 导出需要本机安装兼容的 Chrome/Chromium；不安装时可使用 HTML/CSV。导出使用临时浏览器目录，不复用个人浏览器 profile。系统密钥链集成和脚本沙箱依赖 macOS。

## 开发与验证

```bash
# 后端单元测试
mvn test

# 前端开发（另一个终端运行后端）
npm --prefix frontend ci --ignore-scripts
npm --prefix frontend run dev

# 使用本地 mock 服务与独立数据库的回归
bash tools/apitest-verify/run-all.sh
bash tools/apitest-verify/run-ui.sh
```

浏览器回归需要 Chrome/Chromium，使用独立 profile；不会复用用户浏览器数据。测试脚本所用端口需要空闲，详见脚本内说明。运行前请确保所需 Java/Maven/Node 位于 PATH，或按脚本支持的环境变量配置。

## 数据与适配边界

平台处理的请求、响应、日志、截图和导出报告可能包含使用者自己的敏感数据。它们属于本地运行产物，不应提交到公共仓库。提交前运行 `python3 scripts/check-source.py`，并使用 Gitleaks 扫描；扫描结果仍需要人工复核。

- API、OAuth 和 TCP 操作会访问使用者配置的目标；性能任务也可调用已配置的业务接口。
- 性能通知 webhook 默认为空。配置后，任务异常或判定通知会将运行编号、游戏名称或错误摘要发送到该地址。
- 请求预览、实际 cURL 导出和执行可能解析使用者配置的系统密钥链引用；实际 cURL 应按凭证材料保管。
- 原始日志、数据库备份及完整数据迁移包可能包含请求、响应和凭证。完整迁移包包含配对密钥；自动脱敏不能覆盖所有自定义字段和自由文本，分享前需人工检查。
- 本次仅发布源码，不附带数据库、安装包、真实业务配置、设备数据或原项目提交历史。

默认仅监听 `127.0.0.1`。这是个人工作站工具，未提供面向公网部署的完整身份认证和多租户隔离。

内置 SDK 适配仅描述公开接口/事件协议；该项目不隶属于 SDK 提供方，不附带其服务账号、服务访问权或商业资源。第三方依赖与协议资料来源见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 实现与协作

Java 21 / Spring Boot / SQLite / React / TypeScript。项目采用 AI 辅助开发：需求和测试流程由作者定义，通过分阶段方案评审、功能验收与多 Agent 协作迭代实现。

## 展示与权利说明

项目原创代码保留权利，暂不授予 MIT 或其他通用使用、修改、再分发及商用许可，具体见 [LICENSE](LICENSE)。GitHub 公开仓库允许依其服务条款查看与 Fork；其他使用需求请联系仓库作者。

第三方依赖、字体、图标和派生组件保留各自的许可与版权声明；上述原创代码声明不替代或限制第三方已经授予的权利，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
