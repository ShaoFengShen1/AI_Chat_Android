package com.example.compose.jetchat.data.utils

import android.content.Context
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream

/**
 * 文档处理工具类
 * 用于提取PDF、TXT等文件的文本内容
 */
object DocumentProcessor {
    private const val TAG = "DocumentProcessor"
    private var isInitialized = false
    
    /**
     * 初始化PDFBox
     */
    fun initialize(context: Context) {
        if (!isInitialized) {
            try {
                PDFBoxResourceLoader.init(context)
                isInitialized = true
                Log.d(TAG, "PDFBox初始化成功")
            } catch (e: Exception) {
                Log.e(TAG, "PDFBox初始化失败", e)
            }
        }
    }
    
    /**
     * 从文件中提取文本内容
     * @param fileName 文件名
     * @param inputStream 文件输入流
     * @return 文本内容,如果无法提取则返回null
     */
    fun extractText(fileName: String, inputStream: InputStream): String? {
        return when {
            fileName.endsWith(".txt", ignoreCase = true) -> {
                extractTextFromTxt(inputStream)
            }
            fileName.endsWith(".pdf", ignoreCase = true) -> {
                extractTextFromPdf(inputStream)
            }
            else -> null
        }
    }
    
    /**
     * 从TXT文件提取文本
     */
    private fun extractTextFromTxt(inputStream: InputStream): String? {
        return try {
            inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "TXT文件读取失败", e)
            null
        }
    }
    
    /**
     * 从PDF文件提取文本
     */
    private fun extractTextFromPdf(inputStream: InputStream): String? {
        if (!isInitialized) {
            Log.e(TAG, "PDFBox未初始化")
            return null
        }
        
        return try {
            val document = PDDocument.load(inputStream)
            val stripper = PDFTextStripper()
            val text = stripper.getText(document)
            document.close()
            
            Log.d(TAG, "PDF文本提取成功，长度: ${text.length}")
            text
        } catch (e: Exception) {
            Log.e(TAG, "PDF文本提取失败", e)
            null
        }
    }
    
    /**
     * 限制文本长度
     * @param text 原始文本
     * @param maxLength 最大长度
     * @return 截断后的文本
     */
    fun truncateText(text: String, maxLength: Int = 50000): String {
        return if (text.length > maxLength) {
            val truncated = text.substring(0, maxLength)
            "$truncated\n\n...(文档过长，已截断)"
        } else {
            text
        }
    }
}
