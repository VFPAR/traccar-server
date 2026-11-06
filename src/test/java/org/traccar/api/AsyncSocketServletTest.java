package org.traccar.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketCreator;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.ee10.websocket.server.JettyServerUpgradeRequest;
import org.eclipse.jetty.ee10.websocket.server.JettyServerUpgradeResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.api.security.LoginService;
import org.traccar.config.Config;
import org.traccar.helper.SessionHelper;
import org.traccar.session.ConnectionManager;
import org.traccar.storage.Storage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AsyncSocketServletTest {

    private JettyWebSocketCreator creator(LoginService loginService) {
        AsyncSocketServlet servlet = new AsyncSocketServlet(
                mock(Config.class), new ObjectMapper(), mock(ConnectionManager.class), mock(Storage.class),
                loginService);
        JettyWebSocketServletFactory factory = mock(JettyWebSocketServletFactory.class);
        servlet.configure(factory);
        ArgumentCaptor<JettyWebSocketCreator> captor = ArgumentCaptor.forClass(JettyWebSocketCreator.class);
        verify(factory).setCreator(captor.capture());
        return captor.getValue();
    }

    private JettyServerUpgradeRequest sessionRequest() {
        JettyServerUpgradeRequest request = mock(JettyServerUpgradeRequest.class);
        HttpServletRequest http = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(request.getParameterMap()).thenReturn(Map.of());
        when(request.getHttpServletRequest()).thenReturn(http);
        when(request.getSession()).thenReturn(session);
        when(http.getSession(false)).thenReturn(session);
        when(session.getAttribute(SessionHelper.USER_ID_KEY)).thenReturn(2L);
        return request;
    }

    @Test
    public void disabledSessionUserIsRejected() throws Exception {
        LoginService loginService = mock(LoginService.class);
        doThrow(new SecurityException("User is disabled")).when(loginService).checkUserEnabled(2);
        JettyServerUpgradeResponse response = mock(JettyServerUpgradeResponse.class);
        assertNull(creator(loginService).createWebSocket(sessionRequest(), response));
        verify(response).sendError(anyInt(), anyString());
    }

    @Test
    public void disabledTokenUserIsRejected() throws Exception {
        LoginService loginService = mock(LoginService.class);
        when(loginService.login("tok")).thenThrow(new SecurityException("User is disabled"));
        JettyServerUpgradeRequest request = mock(JettyServerUpgradeRequest.class);
        when(request.getParameterMap()).thenReturn(Map.of("token", List.of("tok")));
        JettyServerUpgradeResponse response = mock(JettyServerUpgradeResponse.class);
        assertNull(creator(loginService).createWebSocket(request, response));
        verify(response).sendError(anyInt(), anyString());
    }

    @Test
    public void enabledSessionUserIsAccepted() throws Exception {
        LoginService loginService = mock(LoginService.class);
        JettyServerUpgradeResponse response = mock(JettyServerUpgradeResponse.class);
        assertNotNull(creator(loginService).createWebSocket(sessionRequest(), response));
    }

}
