package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun googleTranslatePronounReplacement_replacesStandardPronouns() {
    val input = "ผมคิดว่าคุณควรจะรีบไป"
    val replaced = com.example.api.GoogleTranslateHelper.applyCustomPronouns(input, "ฉัน / เธอ")
    assertEquals("ฉันคิดว่าเธอควรจะรีบไป", replaced)

    val replacedKha = com.example.api.GoogleTranslateHelper.applyCustomPronouns(input, "ข้า / เอ็ง")
    assertEquals("ข้าคิดว่าเอ็งควรจะรีบไป", replacedKha)

    val replacedGu = com.example.api.GoogleTranslateHelper.applyCustomPronouns(input, "กู / มึง")
    assertEquals("กูคิดว่ามึงควรจะรีบไป", replacedGu)
  }
}
