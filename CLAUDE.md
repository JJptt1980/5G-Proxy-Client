# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 專案是什麼

非官方 fork（`gw` 分支）of [tokyoxpa3/5G-Proxy-Client](https://github.com/tokyoxpa3/5G-Proxy-Client)：Android SOCKS5 TUN 隧道 Client。使用情境是平板（V2126）連手機（X200 Pro）的 Wi-Fi 熱點，手機跑 5G Proxy Pro（姊妹專案在 `~/Projects/5g-proxy-pro`）當 SOCKS5 伺服器。GW 版的改動清單見 `README-GW.md`；進度與決定見 `docs/STATUS.md`（開工前先讀）。

- `origin` = JJptt1980/5G-Proxy-Client（預設分支 `gw`），`upstream` = 原專案。不對上游開 PR。
- GW 的修改只在 **debug** build type 生效（`applicationIdSuffix ".gw"`、App 名稱來自 `app/src/debug/res`）；release 的套件名與原版相同。實機裝的是 debug APK。

## 指令

需要 JDK 17（Mac 預設是 21，要指定）與 NDK 26.3.11579264：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home && ./gradlew assembleDebug
```

- APK：`app/build/outputs/apk/debug/app-debug.apk`
- Kotlin 單元測試：`./gradlew testDebugUnitTest`；單一測試：`./gradlew testDebugUnitTest --tests 'com.tokyoxpa3.socksclient.GwIdentityTest'`
- Lint（`abortOnError`，舊問題在 `app/lint-baseline.xml`）：`./gradlew lint`
- 原生 C 測試（主機 gcc，不需 Android）：`make -C app/src/test/native test`；單一個：`make -C app/src/test/native run-socks5`（名稱見 Makefile 的 `ALL_TESTS`）
- 其他 CI 會跑的：`make -C app/src/test/native check-symbols`（確認引擎用抽出來的模組，不能平行複製邏輯）、`fuzz`、`sanitize`
- `SecretCipherTest` 是 androidTest，要實機（AndroidKeyStore）。

## 架構重點

```
App --TUN--> C 引擎 (app/src/main/cpp, 單一 epoll 執行緒) --SOCKS5--> Pro 手機 --> 網路
```

- **C 引擎**：`tun_socks.c` 是主迴圈，TCP/UDP/DNS/ICMP/SOCKS5 等解析與狀態機拆在各自的 `*.c` 模組（每個都有對應的 `app/src/test/native/*_test.c`）。通往伺服器的 socket 由 JNI 回呼 Java 建立並 `VpnService.protect()`，避免繞回隧道。JNI 介面在 `jni_bridge.c` ↔ `NativeEngine.kt`。
- **`TunSocksService.kt`**：VpnService＋前台服務，管整個生命週期。GW 加了：
  - 伺服器位址 `gateway`/`auto` → `GatewayResolver` 取目前 Wi-Fi 預設閘道 IPv4（啟動與軟重連都會重新解析）。
  - **自動判別 Pro**：開隧道前 `probePro` 對 gateway:埠做 SOCKS5 打招呼；Pro 不在就不開 TUN，服務停在「等待 Pro」，每 10 秒＋換 Wi-Fi 時重試；運作中 Pro 消失就關隧道回等待。只有按停止才完全結束。
  - 重連固定 3 秒。
- **跟 Pro 報名**（`GwIdentity.kt`）：探測時順便 CONNECT `<8碼ID>.<名字hex>.gw-id.invalid`，讓 Pro GW 的「誰在用」認得這台；Pro 端的對應解析在 5g-proxy-pro 的 `socks5_protocol.c`。改這個格式要兩邊一起改。
- **UI**（`MainActivity.kt`，程式碼建 UI、無 XML layout）：鎖直式單頁；狀態卡→連線摘要卡→[連線設定][進階設定] 滑出面板→底部主按鈕。設定存在 SharedPreferences，`Config.kt`/`Profile.kt` 管讀寫與設定檔匯出入。

## 慣例

- 回覆、文件、commit 訊息用繁體中文。
- 不 commit `keystore.properties`、`*.jks`、APK（`dist/` 下的 APK 只是本地產物）。
- 實機：X200 Pro 只裝 Pro、V2126 只裝這個 Client；adb 一律 `-s <序號>`。
