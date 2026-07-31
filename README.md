# LinkVerse / 知链空间

LinkVerse 是一个面向图书交易与知识社区的智能微服务平台。系统以可靠交易和内容互动为核心，通过统一的用户行为数据连接交易域、论坛域与推荐服务，为用户提供可解释、可迭代的个性化发现体验。

## 技术栈

- **后端：** Java 21、Spring Boot、Spring Cloud Gateway、Spring Security、Spring Cloud Alibaba、Nacos
- **推荐服务：** Python 3.12、FastAPI、PyTorch、Faiss，保留召回、粗排、精排和重排四阶段架构
- **数据与消息：** MySQL、Redis、RabbitMQ；Elasticsearch 按全文检索需求启用
- **可观测性：** Micrometer、OpenTelemetry、Grafana、Loki、Tempo
- **工程环境：** Maven、Docker、Docker Compose
