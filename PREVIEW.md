# Android 开发与预览构建

需要 Java 17、Android SDK 35 和 Build Tools 35.0.0。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Debug 包使用 `.dev` 后缀，产物为 `app/build/outputs/apk/debug/app-debug.apk`。首次启动后由使用者填写服务地址与个人凭证，模型密钥仅放在服务器。

如需固定签名的预览版，自行提供：

- `MOZHI_PREVIEW_KEYSTORE`
- `MOZHI_PREVIEW_STORE_PASSWORD`
- `MOZHI_PREVIEW_KEY_ALIAS`
- `MOZHI_PREVIEW_KEY_PASSWORD`

```sh
./gradlew :app:assemblePreview :app:testPreviewUnitTest
```

产物为 `app/build/outputs/apk/preview/app-preview.apk`，包名带 `.preview` 后缀。后续覆盖更新须复用同一签名并递增版本号。

[Android APK 工作流](.github/workflows/android-apk.yml) 默认仅构建、测试和上传开发包；不引用原应用的签名秘密。GitHub Actions 成功后，可在相应运行的 Artifacts 下载 APK。它不能覆盖使用不同签名的旧安装，也不会自动迁移旧应用资料。正式发布见 [RELEASE.md](RELEASE.md)。
