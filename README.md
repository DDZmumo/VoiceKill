# VoiceKill

> 说到「危险词」就当场暴毙。危险词每 30 秒换一组，其中一部分甚至会「实时更新」。

Minecraft **1.21.11** / Fabric 模组。语音识别走 [Shriek](https://modrinth.com/mod/shriek)（Vosk 离线模型），
说到本轮任意一个危险词就会被服务端 `kill()`。

![控制台](docs/console-desktop.png)

## 声明
- 本 Mod 的功能设计，测试迭代由 [L\_FLA5H](https://github.com/L-FLA5H) 完成，代码开发由 AI 完成，

- 并且，现有的此版本有非常多bug，包括但不限于麦克风无法正常使用、语音识别不准确、危险词展示不全。目前的版本只能用于朋友之间乐一乐。

## 功能

- **每轮一组危险词**（默认 12 个），说出任意一个直接带带带
- **智能选词** —— 上一轮高频 ×3 + 整局累计 ×1，越常说越容易出
- **抗误识别** —— 谐音（`ad` → 哎弟）、同音字（她 → 他）、编辑距离（你好呀装装 → 你好呀中中）、
  多候选回退、被停顿切开的词自动拼接（秒 + 切 → 秒切）
- **四角 HUD** —— 本轮词、倒计时、言多必失榜，常驻显示
- **网页控制台** —— `http://127.0.0.1:38471/` 改词库、看统计、模拟输入
- **游戏内选麦克风** —— F8，选完立即生效，下次启动自动沿用

## 环境要求

| 东西 | 版本 |
| --- | --- |
| Minecraft Java 版 | **1.21.11** |
| Fabric Loader | **0.17.3+** |
| Java | **21** |

> 0.17.3 是 Fabric API 0.141.6 的硬性下限，不是本模组的要求。

## 安装

1. 把 **4 个模组**丢进 `mods`：`voicekill`、[Fabric API](https://modrinth.com/mod/fabric-api)、
   [Shriek](https://modrinth.com/mod/shriek)、[Architectury API](https://modrinth.com/mod/architectury-api)
2. 中文模型（约 42MB）首次启动自动下载，也可以手动放到 `<游戏目录>/shriek/vosk/vosk-model-small-cn-0.22/`
3. 启动游戏

**Shriek 是双端模组**，单人游戏也要装；联机时服务端和每个客户端都要装齐这 4 个。

## 使用

```
/voicekill start     开始（别名 /vk start）
/voicekill stop      停止
/voicekill status    查看状态
```

模组默认**不启用**，不输入 start 不会判定、也不会统计词频。

F8 选录音设备，选完立即生效，**下次启动会自动用同一个设备**。

## 配置

`config/voicekill.json`，常改的几项：

| 字段 | 说明 |
| --- | --- |
| `wordsPerRound` | 每轮几个危险词（1~20） |
| `highFreqPerRound` | 其中几个是「我/你/他」这类高频词 |
| `intervalSeconds` | 换词间隔（秒） |
| `smartPickChance` | 智能选词概率 0~100 |
| `audioDevice` | 录音设备名，留空 = 系统默认 |
| `voskModel` | Vosk 模型名 |

改完重启游戏生效；网页控制台上改的那几项立刻生效。

## 构建

```bash
./gradlew build
```

产物在 `build/libs/voicekill-<版本>.jar`。

## 致谢

- [Shriek](https://modrinth.com/mod/shriek)（MIT）—— 语音识别与网络通道
- [Vosk](https://alphacephei.com/vosk/)（Apache-2.0）—— 离线识别模型
- [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) —— 「自己接管录音才能热切换设备」的思路来自它

## 许可

[MIT](LICENSE) © 2026 L_FLA5H
