# 艾泉双端品牌图标

两端文字品牌统一为“艾泉 / AquaFlow”；“订水”和“工作台”是功能名称。

- `aiquan-customer-crop-20261101.png`：用户确认的 A 顾客端原始裁剪，600×510；运行图 `miniapp-user/assets/logo/aiquan-mark.png` 按原比例缩为 320×272。
- `aiquan-delivery-crop-20261101.png`：用户确认的 Q 配送端原始裁剪，700×475；运行图 `miniapp-delivery/assets/logo/aiquan-delivery-mark.png` 按原比例缩为 336×228。

原始裁剪只用于留存，不进入小程序包；运行图片的源文件路径不变，页面以 `aspectFit` 显示。

`aiquan-customer-master.png` 和 `aiquan-delivery-reference.png` 为旧版设计留档，不再作为当前运行图的来源。

`aiquan-wordmark-source.png` 为原始首版品牌图矩形裁剪（原图像素坐标 346,600 至 910,940，不含图标），保持原白底；两端登录运行资源仅等比缩放，失败时回退可读文字。

运行图使用透明 PNG；保留原始裁剪母版。背景蒙版按图形区域保留白色水波、高光和定位孔；原图接近纯白的渐变与背景难以严格区分，局部边缘按原轮廓处理。
