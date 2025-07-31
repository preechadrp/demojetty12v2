package com.example;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.EnumSet;
import java.util.concurrent.Executors;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class Main {

	static int server_port = 8080;
	static Server server = null;

	public static void main(String[] args) throws Exception {

		try {

			// == ตัวอย่าง
			// ใช้ jetty-ee10-webapp 12.0.23
			// การหา resource แบบปลอดภัย
			// ทำ stop gracefull
			
			System.out.println("Start jetty server");

			var threadPool = new QueuedThreadPool();
			//กำหนดให้ทำงานแบบ Virtual Threads
			//threadPool.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());
			// สามารถกำหนดชื่อ prefix ของ Virtual Threads ได้เพื่อการ Debug
			threadPool.setVirtualThreadsExecutor(
					Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("jetty-vt-", 0).factory()));

			server = new Server(threadPool);

			addConnector(false, false);
			addContext();

			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				try {
					// ใช้เวลาหยุดเซิร์ฟเวอร์
					server.setStopTimeout(60 * 1000l);// รอ 60 นาทีก่อนจะบังคับปิด
					server.stop();
					System.out.println("Jetty server stopped gracefully");
				} catch (Exception e) {
					e.printStackTrace();
				}
			}));

			server.start();
			server.join();

		} catch (Exception e2) {
			e2.printStackTrace();
		}

	}

	public static void addConnector(boolean useHttps, boolean useTrustStore) throws Exception {

		if (!useHttps) {

			ServerConnector httpConnector = new ServerConnector(server);
			httpConnector.setPort(server_port);
			server.addConnector(httpConnector);

		} else { // เมื่อต้องการทำ https  หมายเหตุยังไม่ทดสอบ

			// ======== https =======
			// Setup SSL
			SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();

			// กำหนด KeyStore (สำหรับ Server Certificate)
			// ตรวจสอบให้แน่ใจว่าไฟล์ keystore.jks อยู่ใน classpath หรือระบุพาธที่ถูกต้อง
			URL keystoreUrl = Main.class.getClassLoader().getResource("/keystore.jks");
			if (keystoreUrl == null) {
				throw new IllegalStateException("keystore.jks not found in classpath. Please ensure it's in src/main/resources or similar.");
			}
			sslContextFactory.setKeyStorePath(keystoreUrl.toExternalForm());
			sslContextFactory.setKeyStorePassword("mykeystore");
			sslContextFactory.setKeyManagerPassword("mykeystore");

			String[] allowedCiphers = {
					"TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
					"TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
					"TLS_DHE_RSA_WITH_AES_256_GCM_SHA384",
					"TLS_DHE_RSA_WITH_AES_128_GCM_SHA256"
					// เพิ่ม Cipher Suites อื่นๆ ที่คุณต้องการ (และรองรับใน JVM ของคุณ)
					// หลีกเลี่ยง Cipher Suites ที่มี SHA1, RC4, 3DES, หรือไม่มี Forward Secrecy
			};
			if (allowedCiphers.length > 0) {
				sslContextFactory.setIncludeCipherSuites(allowedCiphers);
			}

			// กำหนด Protocol ที่อนุญาต (Allowed Protocols) ***
			// ควรใช้ TLSv1.2 และ TLSv1.3 เท่านั้น หลีกเลี่ยง SSLv3, TLSv1, TLSv1.1
			sslContextFactory.setIncludeProtocols("TLSv1.2", "TLSv1.3");

			if (useTrustStore) {

				URL truststoreUrl = Main.class.getClassLoader().getResource("/truststore.jks");
				if (truststoreUrl != null) {
					sslContextFactory.setTrustStorePath(truststoreUrl.toExternalForm());
					sslContextFactory.setTrustStorePassword("password"); // รหัสผ่าน TrustStore

					// ถ้าต้องการให้เซิร์ฟเวอร์ร้องขอ Client Certificate (Mutual TLS)
					// sslContextFactory.setNeedClientAuth(true); // บังคับให้ไคลเอนต์ส่งใบรับรอง
					// sslContextFactory.setWantClientAuth(true); // ร้องขอแต่ไม่บังคับ
				} else {
					System.out.println("Not found truststore.jks");
				}

			}

			// สร้าง SslConnectionFactory ---
			// SslConnectionFactory ทำหน้าที่จัดการการเชื่อมต่อ SSL/TLS
			SslConnectionFactory sslConnectionFactory = new SslConnectionFactory(sslContextFactory, "http/1.1");

			// Setup HTTPS Configuration
			HttpConfiguration httpsConf = new HttpConfiguration();
			httpsConf.setSecurePort(server_port);
			httpsConf.setSecureScheme("https");
			httpsConf.addCustomizer(new SecureRequestCustomizer()); // adds ssl info to request object

			// Establish the HTTPS ServerConnector
			ServerConnector httpsConnector = new ServerConnector(
					server,
					sslConnectionFactory,
					new HttpConnectionFactory(httpsConf));
			httpsConnector.setPort(server_port);

			server.addConnector(httpsConnector);

		}

	}

	private static void addContext() throws URISyntaxException {

		//ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
		ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);//api ไม่จำเป็นต้องใช้ session
		context.setContextPath("/");
		if (context.getSessionHandler() != null) {
			context.getSessionHandler().setMaxInactiveInterval(900);//กรณีใช้ ServletContextHandler จะผ่าน ,test 30/7/68
		}

		addServlet(context);// add servlet
		addWebFilter(context);// add filter

		server.setHandler(context);

	}

	private static void addServlet(ServletContextHandler context) {

		context.addServlet(new jakarta.servlet.http.HttpServlet() {

			private static final long serialVersionUID = -1079681049977214895L;

			@Override
			protected void doGet(HttpServletRequest request, HttpServletResponse response)
					throws ServletException, IOException {

				System.out.println("Request handled by thread: " + Thread.currentThread().getName());
				System.out.println("call /api/blocking");
				if (request.getRequestedSessionId() != null) {
					System.out.println("request.getSession().getId() : " + request.getSession(true).getId());
					System.out.println("session timeout : " + request.getSession().getMaxInactiveInterval());// seconds unit
					response.setStatus(HttpServletResponse.SC_OK);
				}

				response.setContentType("application/json");
				response.getWriter().println("{ \"status\": \"ok\"}");

			}

		}, "/api/blocking");// test link = http://localhost:8080/api/blocking

	}

	private static void addWebFilter(ServletContextHandler context) {

		context.addFilter(new Filter() {

			@Override
			public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
					throws IOException, ServletException {

				System.out.println("hello from filter");

				chain.doFilter(request, response);

			}

		}, "/api/*", EnumSet.of(DispatcherType.REQUEST));

	}

}
