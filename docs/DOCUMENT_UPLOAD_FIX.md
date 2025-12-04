# 文档上传功能修复说明

## 问题描述
之前上传PDF、TXT、DOC、DOCX文件时,AI无法读取文件内容,因为:
1. 文件内容被读取为Base64但**没有发送给AI**
2. 只发送了文件名和用户问题,AI看不到文件内容
3. PDF等二进制文件没有被提取为文本

## 已实施修复

### 1. 添加PDF文本提取库
- 添加 `pdfbox-android:2.0.27.0` 依赖
- 自动从PDF文件中提取文本内容

### 2. 创建DocumentProcessor工具类
位置: `app/src/main/java/com/example/compose/jetchat/data/utils/DocumentProcessor.kt`

功能:
- ✅ 从TXT文件提取UTF-8文本
- ✅ 从PDF文件提取文本(使用PDFBox)
- ✅ 自动限制文本长度(最大50000字符)
- ✅ 处理提取失败的情况

### 3. 修改ChatScreen文件读取逻辑
文件类型处理:
```
TXT文件 → 提取文本 → "TEXT:文本内容"
PDF文件 → 提取文本 → "TEXT:文本内容"
        → 失败时 → "PDF_BASE64:base64数据"
DOC/DOCX → "DOC_BASE64:base64数据"
其他文件 → "FILE_BASE64:base64数据"
```

### 4. 修改ChatViewModel发送逻辑
根据文件类型构造消息:

**TXT/PDF提取成功:**
```
文档名: example.pdf

文档内容:
[实际提取的文本内容]

用户问题: 总结这个文档
```

**二进制文件(提取失败):**
```
文档名: example.pdf (PDF文档)

文档Base64数据:
[base64编码的文件内容]

用户问题: 总结这个文档

请根据文档内容回答用户问题。注意:上面是Base64编码的PDF文档内容。
```

## 测试步骤

1. **测试TXT文件:**
   - 上传一个TXT文件
   - AI应该能读取并分析文本内容

2. **测试PDF文件:**
   - 上传一个PDF文件
   - 检查日志:`文档文本提取成功`
   - AI应该能读取PDF中的文本

3. **测试DOC/DOCX文件:**
   - 上传Word文档
   - 会使用Base64编码发送
   - AI会看到base64数据(某些AI模型可能无法解析)

## 注意事项

1. **PDF文本提取限制:**
   - 仅支持基于文本的PDF
   - 扫描版PDF(图片)无法提取文字
   - 需要OCR处理的PDF会提取失败

2. **文本长度限制:**
   - 自动截断超过50000字符的文档
   - 避免超出AI模型的上下文限制

3. **DOC/DOCX支持:**
   - 当前使用Base64编码
   - 需要AI模型支持base64解码
   - 未来可添加Apache POI库进行文本提取

4. **性能考虑:**
   - 大文件(>10MB)读取和提取可能需要时间
   - 在IO线程中处理,不会阻塞UI

## 日志输出

成功提取文本:
```
文档文本提取成功: example.pdf, 长度: 1234 字符
```

使用Base64编码:
```
文档已编码: example.docx, 大小: 56789 bytes
```

## 未来改进

1. 添加Apache POI支持DOC/DOCX文本提取
2. 添加OCR支持扫描版PDF
3. 支持更多文档格式(PPT、Excel等)
4. 文档预处理(去除多余空白、格式化等)
5. 文档分块处理超大文件
