<div align="center">

# 哔哩终端-Fix

<img src="specialicon\j_platlogo_green_smile.webp" width="120" height="120" alt="Wriggle Bean Logo"/>

轻量的第三方B站Android客户端

在原仓库 [BiliClient](https://github.com/huanli233/BiliClient) 的基础上进行了功能修复并加入了新功能。

</div>

# 介绍
这是一个**极其轻量级**的**B站客户端**，名字来源于原神中的“虚空终端”。使用 `java` + `xml`开发，最低支持**安卓4.0.4**。

目前**无兼容性问题且经过测试**的最低安卓版本为**安卓4.4**。

本项目借鉴了 [WearBili](https://github.com/SpaceXC/WearBili) 和 [腕上哔哩](https://github.com/luern0313/WristBilibili) 的部分开源代码和它们收集的部分 API 。

修复部分参考了 [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus) 和原项目的实现方案。

播放视频可选择使用内置播放器、小电视播放器或凉腕播放器。

# 维护说明

> 本Fork项目以稳定性和兼容性为优先目标，
> 不会主动引入大规模重构或重量级框架。

> 如有新的构想或功能建议，请优先提交至原项目仓库。

# 该Fork新增与修复的内容

- **新增内容**
  - 新增 **数据迁移 / 导出 / 导入** 功能
  - 新增 **专栏历史记录** 功能
  - 新增 **专栏内跳转** 功能

- **显示与阅读体验**
  - 支持更丰富的专栏文字渲染（颜色支持，粗体，删除线支持等）
  - 优化专栏布局
  - 调整整体UI配色

- **兼容性与稳定性**
  - 增强 **安卓 4.x 设备可用性**
    - 修复在 Dalvik 虚拟机下的部分崩溃问题
  - 修复多处网络与解析异常（专栏，动态等部分接口导致的报错等问题）