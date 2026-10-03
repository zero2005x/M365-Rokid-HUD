# GitHub Pages 官網

靜態網站，無需 npm 或建置工具。預設繁體中文，可切換英文與簡體中文。

## 本機預覽

在 Repo 根目錄執行：

```powershell
python -m http.server 8765 --directory docs
```

開啟 http://localhost:8765 。亦可直接開啟 `docs/index.html`。

## GitHub Pages 部署

1. 將 README、`doc/` 文件變更及 `docs/` 提交並推送到 `main`。
2. GitHub → Settings → Pages → Build and deployment。
3. Source 選 **Deploy from a branch**；Branch 選 **main**、目錄選 **/docs**，儲存。
4. 等待 Pages 部署成功後，開啟 https://zero2005x.github.io/M365-Rokid-HUD/ 。

`.nojekyll` 讓檔案直接作為靜態資產發布。網站沒有 CDN 腳本或字型依賴。
網站外的開發文件使用 GitHub 連結，因為 `/doc` 不在 `/docs` 的發布範圍。

## 更新內容

- 三語文案集中在 `assets/app.js`，新增文案需同時補齊三個語言。
- `index.html` 保留完整繁中內容，JavaScript 未啟用時仍可閱讀與下載。
- 網站使用的截圖在 `assets/images/{zh-TW,en-US,zh-CN}/`，來源為
  `doc/play-store/` 的前四張截圖；App 截圖更新後需同步複製。
- 截圖為 Demo Ride 模擬資料，不代表實車測試結果。
- 車型資訊以 `doc/MODEL_SUPPORT.md` 為準，須區分辨識、可連線、可讀取及驗證狀態。
- 官網不包含 Rokid 金鑰、授權檔或個人憑證。
