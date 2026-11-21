# 狀態（GW fork）

## 目前進度
- 分支 `gw`（從上游 987f28a 開出）：伺服器位址填 `gateway`/`auto` 時自動用 Wi-Fi 預設閘道 IPv4；預設值改為 `gateway`。
- debug 版套件名稱加 `.gw`、App 名稱「5G Proxy Client GW」，可與原版並存。
- 主畫面改版（MainActivity.buildUi／updateStatus）：最上面是狀態卡（灰＝未連線、黃＝連線中、綠＝已連線並顯示實際 IP、紅＝錯誤），下面是大的藍色啟動／紅色停止按鈕；伺服器、隧道模式用分組卡片；DNS、認證、其他、設定檔收進「進階設定」，預設收起，展開狀態存在偏好設定 ui_advanced_expanded。
- TunSocksService 新增 currentServerIp，給狀態卡顯示實際連到的 IP。
- 已確認 `./gradlew assembleDebug`（JDK 17）可編譯。
- 2026-11-21 實機驗證成功（vivo V2126 連手機熱點、手機跑 5G Proxy Pro）：gateway 自動抓到閘道、狀態卡顯示綠色「已連線」。

## 已定案的決定
- 保留原 LICENSE（MIT，版權屬原作者），說明寫在 README-GW.md。
- commit 使用 JJptt1980 的 GitHub noreply 信箱。

- 不對上游開 PR（2026-11-21 決定），只維護自己的 fork。
- 隧道執行中欄位不另外做變暗效果（2026-11-21 決定）。

## 遠端
- `origin` = https://github.com/JJptt1980/5G-Proxy-Client （fork，預設分支 `gw`）
- `upstream` = https://github.com/tokyoxpa3/5G-Proxy-Client （原專案）

## 下一步
- 無。

## 已知問題
- 無。
