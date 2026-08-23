# OctoDroid 简体中文版

本项目基于 OctoDroid 4.6.15（上游提交 `39f03ffe`）制作，遵循原项目 Apache License 2.0 许可证。

## 本地化内容

- 新增 `values-zh-rCN` 简体中文资源。
- 调整资源打包配置，使 APK 同时包含英文和简体中文。
- 保留原有英文作为尚未翻译文本的回退语言，避免生硬的中英混排。
- 添加可重复运行的 `tools/generate_zh_cn.py` 翻译生成脚本。
- 源码自行构建时可不提供上游私有 OAuth 凭据；访问令牌登录仍可使用。

当前版本是第一轮本地化预览，共处理 581 项资源，其中 324 项已有中文版本。建议在真机测试后继续校对剩余长句、事件动态和错误提示。

## 构建

需要 JDK 21、Android SDK 及网络连接：

```bash
./gradlew assembleDebug
```

生成的调试安装包位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

自行构建版本默认使用访问令牌登录。OAuth 登录需要在 `app/client.properties` 中配置上游 GitHub OAuth 应用的 Client ID 与 Client Secret。
