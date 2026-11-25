# KnowEngine

KnowEngine 是一个 Java 21 知识库问答项目。它将文档和表格转成可检索的知识，保留版本，并让回答附上可回看的原文证据。

## 可以体验什么

- **带引用的 RAG 问答**。Elasticsearch 并行执行关键词和向量召回，RRF 融合候选，BGE 重排后将证据交给语言模型。进度、引用和答案通过 SSE 返回。
- **文档管理**。支持 Markdown、TXT、PDF、Word、CSV 和 Excel，可查看分段、切换版本和停用索引。PDF 和 DOCX 的解析使用 MinerU。
- **知识工作台**。可在新版本生效前并排试问，与旧版本核对差异。点击引用可查看对应版本的段落或 PDF 页。
- **结构化查询**。表格交给嵌入式 DuckDB 计算，可查看来源行。跨资料问答保留两步搜索的证据。
- **RAG 评测与本机 MCP**。支持 JSONL 题集、评测结果对照和 CSV 导出。本机 MCP 在 /mcp 提供三个只读工具。

## 数据怎么走

上传后，原件和解析结果写入 MinIO，文档状态与片段写入 MySQL。切分事务提交后，异步任务生成向量并写入 Elasticsearch。问答时按会话读取 Redis 中的近期历史，缓存未命中时回源 MySQL。检索到的片段连同版本信息进入生成流程。

| 组件 | 本机默认入口 | 用途 |
| --- | --- | --- |
| MySQL | 127.0.0.1:3306 | 文档版本、分段、聊天与评测记录 |
| Redis | 127.0.0.1:6379 | 会话缓存和 Redisson 锁 |
| Elasticsearch | 127.0.0.1:9200 | 关键词和向量检索 |
| MinIO | 127.0.0.1:9090 | 原文件及解析结果 |
| MinerU | 127.0.0.1:8000 | PDF 和 Word 解析 |
| KnowEngine | 127.0.0.1:8009 | Web 页面和 API |

## 在 Windows 上用 IDEA 启动

1. 安装 JDK 21 和 Maven，准备上表中的依赖。首次使用一个空数据库时执行 [建表脚本](scripts/init-schema.sql)，默认库名为 know_engine_learning。
2. 把 [配置样例](config/local.example.properties) 复制为 config/local.properties，填入本机的数据库和 MinIO 凭据。如果 Redis 在虚拟机上，填写它的 IP、端口和密码。在 Windows 用户环境变量中设置 DEEPSEEK_API_KEY，并在设置后启动 IDEA。
3. 从项目根目录运行下列命令。模型脚本下载固定版本的 BGE 重排模型和 tokenizer，并分别验证 SHA-256。[模型来源](https://huggingface.co/onnx-community/bge-reranker-v2-m3-ONNX/tree/90213ffc6a8e6f051a6331269a0f5526cdd896f6)

~~~powershell
if (-not (Test-Path -LiteralPath 'config\local.properties')) {
    Copy-Item -LiteralPath 'config\local.example.properties' -Destination 'config\local.properties'
}
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\fetch-reranker.ps1
mvn test
~~~

4. 用 IDEA 打开 pom.xml，选择 JDK 21，加载 Maven 项目。依赖就绪后运行 KnowEngineApplication-local。
5. 检查 [后端健康状态](http://127.0.0.1:8009/actuator/health)，再进入下列页面。

### 已安装依赖的 Windows 启动脚本

启用 MySQL 和 Redis 后，可以用下列脚本启动 Elasticsearch、MinIO 和 MinerU。脚本默认使用 D:\develop\know-engine-services，可通过 KNOW_ENGINE_SERVICES_DIR 指定其他目录。该目录应含 elasticsearch-9.5.2、minio\minio.exe 和 mineru-env\Scripts\mineru-api.exe。MinerU 的 Python 依赖版本见 [锁定清单](scripts/mineru-requirements.lock.txt)。

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-local.ps1 -SkipApp
~~~

也可以在依赖已启动时，设置 JAVA_HOME 指向 JDK 21，从项目根目录运行 mvn spring-boot:run。项目从当前工作目录读取 config/local.properties。

## 浏览器入口和演示

| 页面 | 链接 |
| --- | --- |
| 上传文档 | [upload.html](http://127.0.0.1:8009/upload.html) |
| 文档管理 | [document.html](http://127.0.0.1:8009/document.html) |
| AI 对话 | [chat.html](http://127.0.0.1:8009/chat.html) |
| 知识工作台 | [knowledge.html](http://127.0.0.1:8009/knowledge.html) |
| RAG 评测 | [eval-report.html](http://127.0.0.1:8009/eval-report.html) |

上传 [园区样例](src/main/resources/testFile/01_曙光智造园区概览.md) 并切分，等文档显示 VECTOR_STORED 后提问“曙光智造园区的内部代号是什么？”。预期答案是 Aurora-7，可点击引用回看原文。也可在[样例目录](src/main/resources/testFile)选资产清单或事件记录做表格计算。

## 项目结构

~~~text
src/main/java/know/engine/
  document/   文档解析、分块、版本与向量管理
  chat/       会话、记忆与流式回答
  rag/        查询改写、召回、融合与重排
  knowledge/  草稿试问、证据预览、DuckDB、跨资料问答和 MCP
  eval/       题集、指标和评测报告
src/main/resources/static/  Web 界面
src/test/                   工作台接口与草稿版本测试
scripts/                    本机启动、建库和模型下载工具
~~~

自动测试通过隔离环境验证草稿指针、版本试问、表格计算、引用定位、分步问答和 MCP。模型推理、存储与索引服务在测试中使用替身。完整链路可用浏览器样例验证。
