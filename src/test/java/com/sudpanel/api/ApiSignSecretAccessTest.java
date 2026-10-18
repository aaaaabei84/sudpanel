package com.sudpanel.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sudpanel.web.ApiTestController;
import com.sudpanel.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** In-process Servlet authorization fixtures; no listener, LAN probe, database or real secret. */
class ApiSignSecretAccessTest {
    private static final String FIXTURE_VALUE = "FICTIONAL_SIGN_SECRET_FOR_ACCESS_TEST";
    private ApiRepository repository;
    private ApiRunner runner;
    private MockMvc mvc;

    @BeforeEach void setup() {
        repository = mock(ApiRepository.class);
        runner = mock(ApiRunner.class);
        mvc = MockMvcBuilders.standaloneSetup(new ApiTestController(repository, runner))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.1.2.3", "::1", "::ffff:127.0.0.1"})
    void loopbackMayReadOnlyTheRequestedEnvironment(String address) throws Exception {
        when(repository.getSignConfig(17)).thenReturn(new ApiSigner.Config(false, "fixture-app", FIXTURE_VALUE, "fixture-nonce", "fixture-game"));
        mvc.perform(get("/api/apitest/sign/secret").param("env", "17")
                        .with(request -> { request.setRemoteAddr(address); return request; }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.appSecret").value(FIXTURE_VALUE));
        verify(repository).getSignConfig(17);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(runner);
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.0.2.1", "198.51.100.9", "2001:db8::1"})
    void nonLoopbackGets403BeforeSecretReadEvenWithSpoofedForwardingHeaders(String address) throws Exception {
        var result = mvc.perform(get("/api/apitest/sign/secret").param("env", "17")
                        .header("X-Forwarded-For", "127.0.0.1").header("Forwarded", "for=127.0.0.1")
                        .with(request -> { request.setRemoteAddr(address); return request; }))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.appSecret").doesNotExist())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(FIXTURE_VALUE);
        verifyNoInteractions(repository, runner);
    }
}
