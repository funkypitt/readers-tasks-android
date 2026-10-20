package com.freedomfighter.readerstasks.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PalettesTest {
    @Test fun `the cycle is the two inks, the eight palettes, then round again`() {
        val seen = generateSequence(ThemeMode.DARK) { Palettes.next(it) }.take(11).toList()
        assertEquals(listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.LAVANDE, ThemeMode.SAUGE, ThemeMode.NORDIQUE, ThemeMode.TERRACOTTA,
            ThemeMode.COQUETTE, ThemeMode.MILITANTE, ThemeMode.TORRIDE, ThemeMode.NATURE, ThemeMode.DARK), seen)
    }

    @Test fun `follow system is never in the cycle and leads to the first ink`() = assertEquals(ThemeMode.DARK, Palettes.next(ThemeMode.SYSTEM))

    @Test fun `an old stored setting keeps its meaning`() {
        assertEquals(ThemeMode.LIGHT, Palettes.parse("LIGHT"))
        assertEquals(ThemeMode.SYSTEM, Palettes.parse("SYSTEM"))
        assertEquals(ThemeMode.DARK, Palettes.parse(null))
        assertEquals(ThemeMode.DARK, Palettes.parse("garbage"))
    }

    @Test fun `the inks keep their accent equal to the text colour, system follows the phone`() {
        assertEquals(0xFFFFFFFF, Palettes.of(ThemeMode.DARK, false).accent)
        assertEquals(0xFF000000, Palettes.of(ThemeMode.LIGHT, true).accent)
        assertEquals(0xFF000000, Palettes.of(ThemeMode.SYSTEM, true).bg)
        assertEquals(0xFFFFFFFF, Palettes.of(ThemeMode.SYSTEM, false).bg)
    }
}
