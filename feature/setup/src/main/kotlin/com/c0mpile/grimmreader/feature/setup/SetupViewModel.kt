package com.c0mpile.grimmreader.feature.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.c0mpile.grimmreader.core.data.server.CertificateInfo
import com.c0mpile.grimmreader.core.data.server.ConnectionResult
import com.c0mpile.grimmreader.core.data.server.ConnectionTester
import com.c0mpile.grimmreader.core.data.server.LoginResult
import com.c0mpile.grimmreader.core.data.server.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SetupUiState(
    val url: String = "",
    val allowCleartext: Boolean = false,
    val testing: Boolean = false,
    val result: ConnectionResult? = null,
    /** Certificate waiting for the user's trust decision. */
    val certificate: CertificateInfo? = null,
    val acceptedPin: String? = null,
    val username: String = "",
    val password: String = "",
    val signingIn: Boolean = false,
    val loginResult: LoginResult? = null,
)

@HiltViewModel
class SetupViewModel
    @Inject
    constructor(
        private val tester: ConnectionTester,
        private val servers: ServerRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(SetupUiState())
        val state: StateFlow<SetupUiState> = _state.asStateFlow()

        fun prefill(url: String) {
            if (_state.value.url.isEmpty() && url.isNotEmpty()) _state.update { it.copy(url = url) }
        }

        fun onUrl(url: String) = _state.update { it.copy(url = url, result = null, acceptedPin = null) }

        fun onAllowCleartext(allow: Boolean) = _state.update { it.copy(allowCleartext = allow, result = null) }

        fun onUsername(value: String) = _state.update { it.copy(username = value, loginResult = null) }

        fun onPassword(value: String) = _state.update { it.copy(password = value, loginResult = null) }

        fun test() {
            val s = _state.value
            _state.update { it.copy(testing = true, result = null, loginResult = null) }
            viewModelScope.launch {
                val result = tester.test(s.url, s.allowCleartext, s.acceptedPin)
                _state.update {
                    it.copy(
                        testing = false,
                        result = result,
                        certificate = (result as? ConnectionResult.UntrustedCertificate)?.certificate,
                    )
                }
            }
        }

        /** The user compared the fingerprint and chose to trust this exact key for this host. */
        fun trustCertificate() {
            val cert = _state.value.certificate ?: return
            _state.update { it.copy(certificate = null, acceptedPin = cert.spkiSha256) }
            test()
        }

        fun rejectCertificate() = _state.update { it.copy(certificate = null) }

        fun signIn() {
            val s = _state.value
            val ok = s.result as? ConnectionResult.Ok ?: return
            _state.update { it.copy(signingIn = true, loginResult = null) }
            viewModelScope.launch {
                val result = servers.login(ok, s.allowCleartext, s.acceptedPin, s.username.trim(), s.password)
                // The password is not kept once it was used.
                _state.update { it.copy(signingIn = false, loginResult = result, password = "") }
            }
        }
    }
