package com.ahanda.techops.noty.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.Cookie;
import io.netty.handler.codec.http.DefaultCookie;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.ServerCookieEncoder;
import io.netty.util.CharsetUtil;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ahanda.techops.noty.Config;
import com.ahanda.techops.noty.NotyConstants;
import com.ahanda.techops.noty.Utils;
import com.ahanda.techops.noty.http.message.FullEncodedResponse;
import com.ahanda.techops.noty.http.message.Request;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * 
 * This class handles the authorization for the request. For publishers, an auth key should be there in the header in
 * every request using which, request is granted. For subscribers, a cookie should be set in the header to validate the
 * request. If the validation fails, server will create the cookie while user login
 *
 */
public class AuthHandler extends SimpleChannelInboundHandler<Request>
{
	// process all the uconfs and populate user-data

	private static final Logger logger = LoggerFactory.getLogger(AuthHandler.class);

	private static String UNAUTH_ACCESS = "Unauthorized access: kindly sign-in again";

	private static String SESSION_EXPIRED = "Session Expired : %d";

	private static String SESSION_DELETED = "Session Deleted Successfully";

	private static String NOT_AUTHORIZED = "Authorization absent, kindly sign-in first";

	private static long validityWindow;

	private Mac mac;

	private static SecretKeySpec sks;

	private boolean isClientValid = false;

	/**
	 * All the security stuff should be common for all the channels, and should be instantiated before processing
	 */
	static
	{
		try
		{
			String macAlgoName = Config.getInstance().getMacAlgoName();
			String secretKey = Config.getInstance().getSecretKey();
			sks = new SecretKeySpec(secretKey.getBytes(), macAlgoName);
			validityWindow = Config.getInstance().getHttpValidityWindow();
		}
		catch (IllegalArgumentException exc)
		{
			logger.warn("Exception while instantiating SecretKeySpec : {} {}", exc.getMessage(), exc.getStackTrace());
		}
	}

	public void initMac()
	{
		try
		{
			/* If MAC is null then init it else reuse the same mac object */
			if (mac == null)
			{
				mac = Mac.getInstance(sks.getAlgorithm());
				mac.init(sks);
				logger.info("Mac Initiated ! {} {}", new Object[] { sks.getEncoded(), mac });
			}
		}
		catch (Exception exc)
		{
			logger.warn("Exception while init MAC : {} {}", exc.getMessage(), exc.getStackTrace());
		}
	}

	public AuthHandler()
	{
		super(false);
	}

	public Map<String, Object> checkCredential(final Map<String, Object> msg)
	{
		String userId = (String) msg.get("userId");
		String password = (String) msg.get("password");

		String sessId = null;

		if (password != null)
		{
			long sessStart = System.currentTimeMillis() / 1000L;
			sessId = getSessId(userId, sessStart);
			Map<String, Object> reply = new HashMap<String, Object>();
			reply.put("userId", userId);
			reply.put("sessStart", sessStart);
			reply.put("sessId", sessId);
			reply.put("status", "ok");

			return reply;
		}

		sessId = (String) msg.get("sessId");
		long sessStart = (long) msg.get("sessStart");
		String nsessId = getSessId(userId, sessStart);
		if (sessId.equals(nsessId))
			msg.put("status", "ok");
		else
			msg.put("status", "error");
		return msg;
	}

	public String getSessId(String userId, long sessStart)
	{
		String cval = String.format("%s&%d", userId, sessStart);
		initMac();
		return new String(Base64.encodeBase64(mac.doFinal(cval.getBytes())));
	}

	@Override
	protected void channelRead0(final ChannelHandlerContext ctx, final Request request) throws Exception
	{
		FullHttpRequest httpReq = request.getHttpRequest();
		String path = request.getRequestPath();
		HttpMethod accessMethod = httpReq.getMethod();

		if ( !isClientAuthenticated() )
			setClientAuthenticated( checkAuthToken( request ) );

		if (path.matches("/logout") && accessMethod == HttpMethod.DELETE)
		{
			sendResponse(ctx, request, HttpResponseStatus.OK, SESSION_DELETED);
			setClientAuthenticated(false);
			ctx.close();
			return;
		}

		if (accessMethod != HttpMethod.POST && !path.equals("/logout"))
		{
			logger.error("Invalid request, Method not supported!");
			FullHttpResponse resp = request.setResponse(HttpResponseStatus.UNAUTHORIZED, ctx.alloc().buffer().writeBytes("Authorization absent, kindly sign-in first".getBytes()));
			ctx.writeAndFlush(new FullEncodedResponse(request, resp));
			return;
		}

        if( isClientAuthenticated() ) {
			ctx.fireChannelRead(request);
			return;
        }

		/*
		 * We need to check the client auth when a new connection is made as in old connection auth is already taken
		 * care off.
		 */
        Set<Cookie> cookies = request.cookies();
        if (cookies == null)
            cookies = new HashSet<Cookie>();

        Cookie sessIdc = null, userIdc = null, sessStartc = null;
        for (Cookie c : cookies)
        {
            switch (c.getName())
            {
            case "sessId":
                sessIdc = c;
                break;
            case "userId":
                userIdc = c;
                break;
            case "sessStart":
                sessStartc = c;
                break;
            default:
                break;
            }
        }

        String sessId = sessIdc != null ? sessIdc.getValue() : null;
        String userId = userIdc != null ? userIdc.getValue() : null;
        long sessStart = sessStartc != null ? Long.valueOf(sessStartc.getValue()) : -1;

        if (path.matches("/login") && (sessIdc == null || userId == null || sessStart == -1))
        {
            String body = httpReq.content().toString(CharsetUtil.UTF_8);

            logger.info("Login request: {} {}", path, body);
            Map<String, String> credentials = Utils.om.readValue(body, new TypeReference<Map<String, String>>()
            {
            });
            userId = credentials.get("userId");

            if (userId == null)
            { // authenticate userId
                logger.debug("Cannot validate User {}, Fix it, continuing as usual !");
                // return null;
            }

            sessStart = System.currentTimeMillis() / 1000L;
            sessId = getSessId(userId, sessStart);

            FullHttpResponse resp = request.setResponse(HttpResponseStatus.OK, Unpooled.buffer(0));
            for (Cookie reqcookie : cookies)
            {
                reqcookie.setMaxAge(0);
            }

            sessIdc = new DefaultCookie("sessId", sessId);
            sessIdc.setHttpOnly(true);
            sessIdc.setPath("/");
            cookies.remove(sessIdc);

            sessIdc.setMaxAge(sessStart + validityWindow);
            cookies.add(sessIdc);

            sessStartc = new DefaultCookie("sessStart", Long.toString(sessStart));
            sessStartc.setHttpOnly(true);
            sessStartc.setPath("/");
            cookies.remove(sessStartc);

            sessStartc.setMaxAge(sessStart + validityWindow);
            cookies.add(sessStartc);

            userIdc = new DefaultCookie("userId", userId);
            userIdc.setHttpOnly(true);
            userIdc.setPath("/");
            cookies.remove(userIdc);

            userIdc.setMaxAge(sessStart + validityWindow);
            cookies.add(userIdc);

            resp.headers().set(HttpHeaders.Names.SET_COOKIE, ServerCookieEncoder.encode(cookies));
        }

        if (sessIdc == null || userIdc == null || sessStartc == null)
        { // invalid request, opensession first
            logger.error("Invalid request, session doesnt exist!");
            FullHttpResponse resp = request.setResponse(HttpResponseStatus.UNAUTHORIZED,
                    ctx.alloc().buffer().writeBytes("Authorization absent, kindly sign-in first".getBytes()));
            ctx.writeAndFlush(new FullEncodedResponse(request, resp));
            return;
        }

        if (sessId == null)
            sessId = sessIdc.getValue();
        if (userId == null)
            userId = userIdc.getValue();
        if (sessStart < 0)
            sessStart = Long.valueOf(sessStartc.getValue());

        String csessid = getSessId(userId, sessStart);

        if (!csessid.equals(sessId))
        {
            logger.error("Invalid credentials {} {}!!", sessId, csessid);
            sendResponse(ctx, request, HttpResponseStatus.UNAUTHORIZED, UNAUTH_ACCESS);
            return;
        }

        long elapseSecs = System.currentTimeMillis() / 1000L - sessStart;
        if (elapseSecs > Config.getInstance().getHttpValidityWindow())
        {
            sendResponse(ctx, request, HttpResponseStatus.UNAUTHORIZED, String.format(SESSION_EXPIRED, elapseSecs));
            return;
        }

        setClientAuthenticated(true);
        request.setUserId(userId);
		ctx.fireChannelRead(request);
	}

	/*
	 * when channel is inactive i.e closed simply remove the user from sessions map
	 */
	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception
	{
		super.channelInactive(ctx);
	}
	
	private boolean checkAuthToken(Request request) throws IOException
	{
		String token = request.getHttpRequest().headers().get("auth-token");
		if (Config.getInstance().getAuthToken().equals(token))
			return true;
		return false;
	}

	private void sendResponse(ChannelHandlerContext ctx, Request req, HttpResponseStatus status, String msg)
	{
		FullHttpResponse resp = new DefaultFullHttpResponse(req.getHttpRequest().getProtocolVersion(), status, ctx.alloc().buffer().writeBytes(msg.getBytes()));
		ctx.writeAndFlush(new FullEncodedResponse(req, resp));
	}

	private boolean isClientAuthenticated()
	{
		return isClientValid;
	}

	private void setClientAuthenticated(boolean isAuth)
	{
		isClientValid = isAuth;
	}
}
