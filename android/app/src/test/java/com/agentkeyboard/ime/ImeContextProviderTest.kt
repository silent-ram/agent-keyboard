package com.agentkeyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/** 密码框检测纯函数测试(EditorInfo 字段为公开数据,可直接构造) */
class ImeContextProviderTest {

    private fun editorInfo(inputType: Int): EditorInfo = EditorInfo().apply { this.inputType = inputType }

    @Test
    fun nullEditorInfo_isNormal() {
        assertEquals(Mode.NORMAL, ImeContextProvider.detect(null))
    }

    @Test
    fun typeNullField_isNullMode() {
        assertEquals(Mode.NULL, ImeContextProvider.detect(editorInfo(InputType.TYPE_NULL)))
    }

    @Test
    fun plainTextField_isNormal() {
        assertEquals(
            Mode.NORMAL,
            ImeContextProvider.detect(editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL)),
        )
    }

    @Test
    fun textVariationPassword_isPassword() {
        assertEquals(
            Mode.PASSWORD,
            ImeContextProvider.detect(editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)),
        )
    }

    @Test
    fun visiblePassword_isPassword() {
        assertEquals(
            Mode.PASSWORD,
            ImeContextProvider.detect(editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)),
        )
    }

    @Test
    fun webPassword_isPassword() {
        assertEquals(
            Mode.PASSWORD,
            ImeContextProvider.detect(editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)),
        )
    }

    @Test
    fun numberPassword_isPassword() {
        assertEquals(
            Mode.PASSWORD,
            ImeContextProvider.detect(editorInfo(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)),
        )
    }

    /** 回归:TYPE_TEXT_VARIATION_URI 与 TYPE_NUMBER_VARIATION_PASSWORD 同为 0x10,地址栏不得被当成密码框 */
    @Test
    fun uriField_isNormal() {
        assertEquals(
            Mode.NORMAL,
            ImeContextProvider.detect(editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)),
        )
    }

    @Test
    fun plainNumberField_isNormal() {
        assertEquals(
            Mode.NORMAL,
            ImeContextProvider.detect(editorInfo(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)),
        )
    }

    /** 文本类的数字变体位值不应被当成数字密码,反之亦然 */
    @Test
    fun phoneField_isNormal() {
        assertEquals(Mode.NORMAL, ImeContextProvider.detect(editorInfo(InputType.TYPE_CLASS_PHONE)))
    }
}
