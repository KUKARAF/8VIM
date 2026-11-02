package inc.flide.vim8.ime.layout.models

import android.view.KeyEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

class CustomKeycodeSpec : FunSpec({
    test("dictate keycodes map from their key codes") {
        CustomKeycode.KEY_CODE_TO_STRING_CODE_MAP[-23] shouldBe CustomKeycode.DICTATE_START
        CustomKeycode.KEY_CODE_TO_STRING_CODE_MAP[-24] shouldBe CustomKeycode.DICTATE_STOP
    }

    test("key codes are unique") {
        CustomKeycode.KEY_CODE_TO_STRING_CODE_MAP.size shouldBe CustomKeycode.entries.size
    }

    context("convert to a keyEvent") {
        withData(
            nameFn = { "${it.first} -> ${it.second}" },
            (CustomKeycode.MOVE_CURRENT_END_POINT_LEFT to KeyEvent.KEYCODE_DPAD_LEFT),
            (CustomKeycode.SELECTION_START to KeyEvent.KEYCODE_DPAD_LEFT),
            (CustomKeycode.MOVE_CURRENT_END_POINT_RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT),
            (CustomKeycode.MOVE_CURRENT_END_POINT_UP to KeyEvent.KEYCODE_DPAD_UP),
            (CustomKeycode.MOVE_CURRENT_END_POINT_DOWN to KeyEvent.KEYCODE_DPAD_DOWN),
            (CustomKeycode.SELECT_ALL to 0),
            (CustomKeycode.DICTATE_START to 0),
            (CustomKeycode.DICTATE_STOP to 0)
        ) { (customKeycode, keyEventCode) ->
            customKeycode.toKeyEvent() shouldBe keyEventCode
        }
    }
})
