# 验证说明

测试使用合成账号、模拟短信发送器及模拟模型，不要求真实模型密钥，也不发送真实短信。历史运行截图、证书信息和设备日志不随开源源码分发。

## Python 服务

为各服务建立 Python 3.12 虚拟环境，在对应目录执行：

```sh
python -m pip install -r requirements-test.txt
python -m pytest -q
```

目录分别为 `server/`、`admin/`、`community/`；建议分别运行，避免两个 `app.py` 的导入路径互相影响。

## Android

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

需要 Java 17、Android SDK 35。设备测试涉及定位、通知、加密存储与系统文件选择器，需要模拟器或真机；不要在存有个人资料的日常安装中执行数据清理测试。

## macOS

```sh
node --test macos/Tests/core.test.cjs
bash macos/scripts/build.sh
```

原生构建和 Swift 策略测试需要 macOS/Xcode 工具链。`macos/Tests/ui.cjs` 是使用模拟桥接的界面回归测试，需要 Playwright 和 Chromium。

## 开源提交检查

```sh
python3 scripts/check-public-files.py
```

脚本检查 Git 跟踪文件中的常见密钥格式、私有配置和数据文件。它是辅助检查，不能代替对新加入文档、图片、测试数据和第三方授权的人工审阅。

真实模型工具调用、视觉识别、短信投递、HTTPS 反向代理和设备通知，需要部署者在自己的环境中验证。自动化单元测试通过不代表已完成这些联调。
