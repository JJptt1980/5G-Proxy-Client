# 狀態（GW fork）

## 目前進度
- 分支 `gw`（從上游 987f28a 開出）：伺服器位址填 `gateway`/`auto` 時自動用 Wi-Fi 預設閘道 IPv4；預設值改為 `gateway`。
- debug 版套件名稱加 `.gw`、App 名稱「5G Proxy Client GW」，可與原版並存。
- 已確認 `./gradlew assembleDebug`（JDK 17）可編譯。

## 已定案的決定
- 保留原 LICENSE（MIT，版權屬原作者），說明寫在 README-GW.md。
- commit 使用 JJptt1980 的 GitHub noreply 信箱。

## 下一步
- fork 到 JJptt1980 並推送 `gw`，fork 預設分支設為 `gw`。
- 不對上游開 PR（2026-11-21 決定），只維護自己的 fork。

## 已知問題
- 尚未在實機上測試閘道偵測。
