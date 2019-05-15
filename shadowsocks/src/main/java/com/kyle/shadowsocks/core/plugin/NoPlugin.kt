package com.kyle.shadowsocks.core.plugin

import com.kyle.shadowsocks.core.Core.app
import com.kyle.shadowsocks.core.R

object NoPlugin : Plugin() {
    override val id: String get() = ""
    override val label: CharSequence get() = app.getText(R.string.plugin_disabled)
}
