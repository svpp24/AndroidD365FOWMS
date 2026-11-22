package com.d365fo.mobileops.presentation

import com.d365fo.mobileops.presentation.common.components.TypedEchoes
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Which owner values a synced text field shows and which it ignores (Compose 1.13 text fields). */
class TypedEchoesTest {

    @Test
    fun `a late echo of earlier typing is not shown over what was typed since`() {
        val echoes = TypedEchoes()
        echoes.sent("1")
        echoes.sent("12")
        // The owner catches up one value at a time: neither puts the field back.
        assertThat(echoes.isEcho("1")).isTrue()
        assertThat(echoes.isEcho("12")).isTrue()
    }

    @Test
    fun `an owner that skips values (conflated state) still counts as catching up`() {
        val echoes = TypedEchoes()
        listOf("1", "12", "123").forEach(echoes::sent)
        assertThat(echoes.isEcho("123")).isTrue()
        // Everything older is done with: a later owner "1" is a change of its own.
        assertThat(echoes.isEcho("1")).isFalse()
    }

    @Test
    fun `the owner clearing the field is its own change and is shown`() {
        val echoes = TypedEchoes()
        listOf("1", "12", "123", "1234").forEach(echoes::sent)
        assertThat(echoes.isEcho("1234")).isTrue()
        // A failed sign-in clears the PIN.
        assertThat(echoes.isEcho("")).isFalse()
    }

    @Test
    fun `a clear that the worker typed (deleting everything) is an echo`() {
        val echoes = TypedEchoes()
        listOf("1", "").forEach(echoes::sent)
        assertThat(echoes.isEcho("")).isTrue()
    }

    @Test
    fun `nothing sent means every owner value is the owner's own`() {
        assertThat(TypedEchoes().isEcho("prefilled")).isFalse()
    }
}
