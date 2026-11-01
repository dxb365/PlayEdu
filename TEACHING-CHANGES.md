# 教学版变更

基于 PlayEdu 主分支 1d4b22dc1a9e43424ab86fe1871f5a04a9b2bfd0，保留上游版权与页面版权标识。

2026-11-01：新增独立学习位置表、自动恢复课时与秒数、暂停和离页保存、独立倍速开关、全站拖动限制、电脑/手机学习状态，以及群晖容器部署与构建验证。

部署说明见 [群晖部署](deploy/synology/README.zh-CN.md)。新增/修改代码中的 Teaching customization 注释标识主要修改；完整差异由 Git 记录。现有完成进度与学习位置独立保存，默认不清空数据。

2026-11-01：新增安卓平板客户端、后台暂停保存、APK构建与模拟器验证。

安卓说明见 [安卓平板课堂](https://github.com/dxb365/PlayEdu/blob/android-pad/playedu-android/README.zh-CN.md)。原生窗口与导航使用 Android WebView，不附带离线视频，不修改上游版权。

2026-11-01：新增免费证书自动申请、续期、校验和平滑加载。

自动证书实现位于 deploy/synology/tls，阿里云授权与一次性设置见 AUTO-TLS.zh-CN.md。DNS 密钥只保留在 NAS，测试使用临时 CA 与虚拟 DNS，不代表实际域名已签发。
