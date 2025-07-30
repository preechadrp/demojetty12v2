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
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.Resources;
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

			var threadPool = new QueuedThreadPool();
			//กำหนดให้ทำงานแบบ Virtual Threads
			//threadPool.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());
			// สามารถกำหนดชื่อ prefix ของ Virtual Threads ได้เพื่อการ Debug
			threadPool.setVirtualThreadsExecutor(
					Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("jetty-vt-", 0).factory()));

			server = new Server(threadPool);

			addConnectorHttp(false, false);
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

	public static void addConnectorHttp(boolean useHttps, boolean useTrutsStore) {

		if (!useHttps) {

			ServerConnector httpConnector = new ServerConnector(server);
			httpConnector.setPort(server_port);
			server.addConnector(httpConnector);

		} else { //เมื่อต้องการทำ https  หมายเหตุยังไม่ทดสอบ

			// ======== https =======
			// Setup SSL
			ResourceFactory resourceFactory = ResourceFactory.of(server);
			SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();

			sslContextFactory.setKeyStoreResource(findKeyStore(resourceFactory));
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
			sslContextFactory.setIncludeCipherSuites(allowedCiphers);

			// กำหนด Protocol ที่อนุญาต (Allowed Protocols) ***
			// ควรใช้ TLSv1.2 และ TLSv1.3 เท่านั้น หลีกเลี่ยง SSLv3, TLSv1, TLSv1.1
			sslContextFactory.setIncludeProtocols("TLSv1.2", "TLSv1.3");

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

	private static Resource findKeyStore(ResourceFactory resourceFactory) {
		String resourceName = "/keystore.jks";
		Resource resource = resourceFactory.newClassLoaderResource(resourceName);
		if (!Resources.isReadableFile(resource)) {
			throw new RuntimeException("Unable to read " + resourceName);
		}
		return resource;
	}

	private static void addContext() throws URISyntaxException {

		ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);

		// add servlet
		addServlet(context);

		// add filter
		addWebFilter(context);

		// set web resource
		URL rscURL = Main.class.getResource("/webapp/");
		Resource baseResource = ResourceFactory.of(context).newResource(rscURL.toURI());
		System.out.println("Using BaseResource: " + baseResource);
		context.setBaseResource(baseResource);
		context.setContextPath("/");
		context.setWelcomeFiles(new String[] { "welcome.html" });
		if (context.getSessionHandler() != null) {
			context.getSessionHandler().setMaxInactiveInterval(900);//กรณีใช้ ServletContextHandler จะผ่าน ,test 30/7/68
		}

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
				if (request.getSession() != null) {
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
