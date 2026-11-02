# 对照英语 · LearnIME

一款 Kotlin Multiplatform 输入法：正常输入中文或英文，在键盘中查看另一种
语言的译文，辅助日常语言学习。正文照常提交给当前 App，译文仅在键盘内展示。
输入、词典、手写、语音及翻译推理均在本机处理。

## 功能

- 中文 26 键／九键拼音：libime 解码、整句候选、音节锁定、错键纠正、
  独立模糊音、用户词典与短语缩写。
- 英文词候选、拼写纠正、句首大写，中英数字混输。
- 中文与英文双向离线翻译；可关闭翻译栏，敏感输入框暂停翻译与学习。
- 剪贴板文字历史、固定与删除，编辑工具、分类符号及基础表情。
- 简繁输出、可选内联组合、单手布局、数字行、主题及按键反馈。
- 中文离线单字手写，支持逐字连续写、候选改选、速度与笔画粗细设置。
- Vosk 中文离线语音输入；首次使用需麦克风权限。

示例：`nihao → 你好`，`woxianghuijia → 我想回家`；
输入 `I want to go home.` 可在翻译栏查看 `我想回家。`。

上述新增设置及输入工具目前主要适用于 Android。iOS 保留原有拼音输入和
OPUS 翻译实现，尚未移植 Android 的全部功能。

## 构建

项目包含 Android App、iOS App／键盘扩展、KMP 共享逻辑和原生输入引擎。
修改过的原生依赖源码随仓库提供，模型与词库通过固定版本脚本下载和校验。

准备资源和构建步骤见 [构建说明](docs/building.md)。
Android 当前为 arm64-v8a，SDK 35，NDK 27.2.12479018，使用 JDK 17。
完整资源准备后运行：

```sh
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:lintDebug :shared:jvmTest
bash scripts/verify-libime.sh
```

Debug APK 位于 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`，
约 1.25 GiB，主要体积来自随包的两套翻译模型。模型和 APK 不存入 Git。

## 实现与限制

Android 翻译默认使用 M2M100 418M 的 INT8 ONNX 转换与电脑端预优化解码器，
ONNX Runtime 本地运行；可切换到保留的 OPUS-MT tiny／LiteRT。
iOS 当前使用 OPUS-MT tiny／LiteRT。模型转换来源和校验记录在 `models/` 中。

手写使用 Zinnia／Tegaki 单字模型，支持逐字连续写；候选准确率仍有限，
不支持并排多字自动分割。语音使用 small-cn 离线模型，口音和噪声条件下的
效果仍需更多测试。英文更正按词频排序，未提供上下文语法纠正。
GIF、贴纸、跨字母滑行解码和图片剪贴板尚未实现。

更多实现边界见 [项目状态](docs/project-status.md)。
仓库不包含个人手机截图、本地验收日志、设备配置或签名密钥。

## 许可

本项目原创代码采用 **GPL-3.0-or-later**，完整文本见 [LICENSE](LICENSE)。
分发修改版或二进制时应按协议提供相应源码及必要构建材料。
第三方代码、词典和模型保留各自许可证，见
[第三方组件说明](THIRD_PARTY_NOTICES.md)。
