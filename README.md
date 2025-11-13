# NotiBridge

## Introduction

NotiBridge is an Android application designed to read notifications from an iOS device using the Apple Notification Center Service (ANCS) over Bluetooth Low Energy (BLE). It can then display these notifications or forward them to other iOS devices using the [Bark](https://github.com/Finb/Bark) notification service. This project allows an Android device to act as a bridge for ANCS notifications.

---

## 简介

NotiBridge 是一个 Android 应用程序，旨在使用苹果通知中心服务（ANCS）通过低功耗蓝牙（BLE）读取来自 iOS 设备的通知。它可以显示这些通知，或使用 [Bark](https://github.com/Finb/Bark) 通知服务将它们转发到其他 iOS 设备。该项目允许 Android 设备作为 ANCS 通知的桥梁。

## Features

-   **Scan for BLE Devices:** Discover nearby Bluetooth Low Energy peripherals.
-   **Connect to iOS:** Establish a connection with an iOS device that is advertising the ANCS service.
-   **Receive Notifications:** Capture and parse notifications sent from the iOS device.
-   **Notification Forwarding:** Forwards incoming notifications to other iOS devices via the Bark service.

---

## 功能

-   **扫描 BLE 设备:** 发现附近的低功耗蓝牙外围设备。
-   **连接至 iOS:** 与正在广播 ANCS 服务的 iOS 设备建立连接。
-   **接收通知:** 捕获并解析从 iOS 设备发送的通知。
-   **通知转发:** 通过 Bark 服务将接收到的通知转发到其他 iOS 设备。

## How to Build

1.  Clone this repository.
2.  Open the project directory in Android Studio.
3.  The IDE should automatically sync the project with the Gradle files.
4.  Select the `ancs` run configuration and build the project.

---

## 如何构建

1.  克隆此仓库。
2.  在 Android Studio 中打开项目目录。
3.  IDE 应自动与 Gradle 文件同步项目。
4.  选择 `ancs` 运行配置并构建项目。

## Acknowledgements

This project is based on and inspired by the work of [brookwillow/ANCSReader](https://github.com/brookwillow/ANCSReader). Many thanks to the original author for their contribution.
Also thanks to [Bark](https://github.com/Finb/Bark) for providing the notification forwarding service.

---

## 致谢

该项目基于 [brookwillow/ANCSReader](https://github.com/brookwillow/ANCSReader) 并受其启发。非常感谢原作者的贡献。
同时也感谢 [Bark](https://github.com/Finb/Bark) 提供了通知转发服务。

## Disclaimer

This application has been primarily tested on a Redmi K60 device. Long-term stability has not been thoroughly evaluated, and it is currently considered a functional test version. Contributions and Pull Requests are highly welcome!

---

## 免责声明

本应用程序主要在 Redmi K60 设备上进行过测试。尚未进行长时间稳定性评估，目前属于功能测试版本。欢迎提交贡献和 Pull Request！
