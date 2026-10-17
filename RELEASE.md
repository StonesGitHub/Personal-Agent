# 自行签名发布 Android

开源版不包含任何既有应用的签名私钥、密码、证书指纹或备案信息。默认应用 ID 为 `com.mozhi.assistant`，可用 Gradle 属性 `-PmozhiApplicationId=你的应用ID` 改为自己的发布身份。

正式构建使用发布者自己的签名文件，通过以下环境变量提供：

- `MOZHI_RELEASE_KEYSTORE`：签名库路径。
- `MOZHI_RELEASE_STORE_PASSWORD`：签名库密码。
- `MOZHI_RELEASE_KEY_ALIAS`：自己的密钥别名。
- `MOZHI_RELEASE_KEY_PASSWORD`：密钥密码。

```sh
./gradlew :app:assembleRelease
```

缺少上述变量时正式构建会停止。密码和密钥不写入源码、构建日志或公开产物；本地签名文件已加入忽略规则。若签名库使用其他格式，可传入相应的 Gradle/Android 签名配置。

发布后应保管好同一份签名库。只有包名和签名相同的安装包才能直接覆盖已有应用；更换签名或包名时先验证加密备份与导入流程。检查产物可使用 `apkanalyzer manifest application-id` 和 `apksigner verify --print-certs`。

构建发布包不代表服务端已经部署，也不会自动配置模型、短信或推送服务。
