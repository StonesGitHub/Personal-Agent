# 第三方组件

运行时依赖与许可证来自下载依赖的 Maven POM 和上游版本源码。

| 组件 | 版本 | 许可证 |
| --- | --- | --- |
| Markwon core / ext-tables / ext-strikethrough | 4.6.2 | Apache License 2.0 |
| commonmark-java / GFM tables / GFM strikethrough | 0.13.0 | BSD 2-Clause |
| AndroidX annotation | 1.1.0 | Apache License 2.0 |

许可证文本随源码与 APK 位于 `app/src/main/assets/licenses/`。AndroidX annotation 使用同一 Apache 2.0 许可证文本，版权归 Android Open Source Project。

- Markwon: https://github.com/noties/Markwon/tree/v4.6.2
- CommonMark: https://github.com/commonmark/commonmark-java/tree/commonmark-parent-0.13.0
- AndroidX: https://developer.android.com/jetpack/androidx

仅测试依赖：JUnit 4.13.2（EPL 1.0）和 org.json 20240303（Public Domain），不打进应用运行时。
