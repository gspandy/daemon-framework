/**
 *
 */
package de.taimos.daemon;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.DailyRollingFileAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.net.SyslogAppender;

import sun.misc.Signal;
import sun.misc.SignalHandler;

/**
 *
 * @author hoegertn
 *
 */
@SuppressWarnings("restriction")
public class DaemonStarter {
	
	private static final AtomicReference<String> daemonName = new AtomicReference<>();
	
	private static final String instanceId = UUID.randomUUID().toString();
	
	private static final Properties daemonProperties = new Properties();
	
	private static final AtomicReference<String> hostname = new AtomicReference<>();
	
	private static final DaemonManager daemon = new DaemonManager();
	
	private static final AtomicReference<String> startupMode = new AtomicReference<>("");
	
	private static final Logger rlog = Logger.getRootLogger();
	
	private static SyslogAppender syslog;
	private static DailyRollingFileAppender darofi;
	private static LogglyAppender loggly;
	private static ConsoleAppender console;
	
	private static final AtomicReference<IDaemonLifecycleListener> lifecycleListener = new AtomicReference<>();
	
	private static final AtomicReference<LifecyclePhase> currentPhase = new AtomicReference<LifecyclePhase>(LifecyclePhase.STOPPED);
	
	
	/**
	 * @return if the system is in development mode
	 */
	public static boolean isDevelopmentMode() {
		return DaemonStarter.startupMode.get().equals(DaemonProperties.STARTUP_MODE_DEV);
	}
	
	/**
	 * @return if the system is in start mode
	 */
	public static boolean isStartMode() {
		return DaemonStarter.startupMode.get().equals(DaemonProperties.STARTUP_MODE_START);
	}
	
	/**
	 * @return if the system is in run mode
	 */
	public static boolean isRunMode() {
		return DaemonStarter.startupMode.get().equals(DaemonProperties.STARTUP_MODE_RUN);
	}
	
	/**
	 * @return the startup mode
	 */
	public static String getStartupMode() {
		return DaemonStarter.startupMode.get();
	}
	
	/**
	 * @return the current {@link LifecyclePhase}
	 */
	public static LifecyclePhase getCurrentPhase() {
		return DaemonStarter.currentPhase.get();
	}
	
	/**
	 *
	 * @return the hostname of the running machine
	 */
	public static String getHostname() {
		return DaemonStarter.hostname.get();
	}
	
	/**
	 * @return the name of this daemon
	 */
	public static String getDaemonName() {
		return DaemonStarter.daemonName.get();
	}
	
	/**
	 * @return the instance UUID for this daemon
	 */
	public static String getInstanceId() {
		return DaemonStarter.instanceId;
	}
	
	/**
	 * @return the daemon properties
	 */
	public static Properties getDaemonProperties() {
		return DaemonStarter.daemonProperties;
	}
	
	private static IDaemonLifecycleListener getLifecycleListener() {
		if (DaemonStarter.lifecycleListener == null) {
			throw new RuntimeException("No lifecycle listener found");
		}
		return DaemonStarter.lifecycleListener.get();
	}
	
	/**
	 * Starts the daemon and provides feedback through the life-cycle listener<br>
	 * <br>
	 *
	 * @param _daemonName the name of this daemon
	 * @param _lifecycleListener the {@link IDaemonLifecycleListener} to use for phase call-backs
	 */
	public static void startDaemon(final String _daemonName, final IDaemonLifecycleListener _lifecycleListener) {
		// Run daemon async
		Executors.newSingleThreadExecutor().execute(new Runnable() {
			
			@Override
			public void run() {
				DaemonStarter.doStartDaemon(_daemonName, _lifecycleListener);
			}
		});
	}
	
	private static void doStartDaemon(final String _daemonName, final IDaemonLifecycleListener _lifecycleListener) {
		final boolean updated = DaemonStarter.currentPhase.compareAndSet(LifecyclePhase.STOPPED, LifecyclePhase.STARTING);
		if (!updated) {
			DaemonStarter.rlog.error("Service already running");
			return;
		}
		
		DaemonStarter.daemonName.set(_daemonName);
		DaemonStarter.addProperty(DaemonProperties.DAEMON_NAME, _daemonName);
		DaemonStarter.addProperty(DaemonProperties.SERVICE_NAME, _daemonName);
		DaemonStarter.lifecycleListener.set(_lifecycleListener);
		
		DaemonStarter.startupMode.set(DaemonStarter.findStartupMode());
		
		// Configure the logging subsystem
		DaemonStarter.configureLogging();
		
		// Set and check the host name
		DaemonStarter.determineHostname();
		
		// Print startup information to log
		DaemonStarter.logStartupInfo();
		
		// handle system signals like HUP, TERM, USR2
		DaemonStarter.handleSignals();
		
		// Load properties
		DaemonStarter.initProperties();
		
		// Change SYSLOG with properties
		DaemonStarter.amendLogAppender();
		
		// Run custom startup code
		try {
			DaemonStarter.getLifecycleListener().doStart();
		} catch (Exception e) {
			DaemonStarter.abortSystem(e);
		}
		
		// Daemon has been started
		DaemonStarter.notifyStarted();
		
		// This blocks until stop() is called
		DaemonStarter.daemon.block();
		
		// Shutdown system
		try {
			DaemonStarter.getLifecycleListener().doStop();
		} catch (Exception e) {
			DaemonStarter.abortSystem(e);
		}
		
		// Daemon has been stopped
		DaemonStarter.notifyStopped();
		
		// Exit system with success return code
		System.exit(0);
	}
	
	@SuppressWarnings("deprecation")
	private static String findStartupMode() {
		final String startmode = System.getProperty(DaemonProperties.STARTUP_MODE);
		if (startmode == null) {
			final String devmode = System.getProperty(DaemonProperties.DEVELOPMENT_MODE);
			// check legacy mode
			if ((devmode != null) && !devmode.equals("true")) {
				return DaemonProperties.STARTUP_MODE_START;
			}
			return DaemonProperties.STARTUP_MODE_DEV;
		}
		return startmode;
	}
	
	private static void notifyStopped() {
		DaemonStarter.currentPhase.set(LifecyclePhase.STOPPED);
		DaemonStarter.rlog.info(DaemonStarter.daemonName + " stopped!");
		DaemonStarter.getLifecycleListener().stopped();
	}
	
	private static void notifyStarted() {
		DaemonStarter.currentPhase.set(LifecyclePhase.STARTED);
		DaemonStarter.rlog.info(DaemonStarter.daemonName + " started!");
		DaemonStarter.getLifecycleListener().started();
	}
	
	private static void logStartupInfo() {
		if (DaemonStarter.isDevelopmentMode()) {
			DaemonStarter.rlog.info("Running in development mode");
		} else {
			DaemonStarter.rlog.info("Running in production mode: " + DaemonStarter.startupMode.get());
		}
		
		DaemonStarter.rlog.info("Running with instance id: " + DaemonStarter.instanceId);
		DaemonStarter.rlog.info("Running on host: " + DaemonStarter.hostname);
	}
	
	private static void determineHostname() {
		try {
			final String host = InetAddress.getLocalHost().getHostName();
			if ((host != null) && !host.isEmpty()) {
				DaemonStarter.hostname.set(host);
			} else {
				DaemonStarter.rlog.error("Hostname could not be determined --> Exiting");
				DaemonStarter.abortSystem();
			}
		} catch (final UnknownHostException e) {
			DaemonStarter.rlog.error("Getting hostname failed", e);
			DaemonStarter.abortSystem(e);
		}
	}
	
	private static void initProperties() {
		try {
			// Loading properties
			final Map<String, String> properties = DaemonStarter.getLifecycleListener().loadProperties();
			if (properties != null) {
				for (final Entry<String, String> e : properties.entrySet()) {
					DaemonStarter.addProperty(e.getKey(), String.valueOf(e.getValue()));
				}
			}
		} catch (final Exception e) {
			DaemonStarter.rlog.error("Getting config data failed", e);
			DaemonStarter.abortSystem(e);
		}
	}
	
	private static void addProperty(final String key, final String value) {
		if (key == null) {
			return;
		}
		String trimKey = key.trim();
		if (DaemonStarter.isDevelopmentMode()) {
			DaemonStarter.rlog.info(String.format("Setting property: '%s' with value '%s'", trimKey, value));
		} else {
			DaemonStarter.rlog.debug(String.format("Setting property: '%s' with value '%s'", trimKey, value));
		}
		DaemonStarter.daemonProperties.setProperty(trimKey, value);
		System.setProperty(trimKey, value);
	}
	
	private static void configureLogging() {
		try {
			// Clear all existing appenders
			DaemonStarter.rlog.removeAllAppenders();
			
			DaemonStarter.rlog.setLevel(Level.INFO);
			
			// only use SYSLOG and DAROFI in production mode
			if (!DaemonStarter.isDevelopmentMode()) {
				DaemonStarter.darofi = new DailyRollingFileAppender();
				DaemonStarter.darofi.setName("DAROFI");
				DaemonStarter.darofi.setLayout(new PatternLayout("%d{HH:mm:ss,SSS} %-5p %c %x - %m%n"));
				DaemonStarter.darofi.setFile("log/" + DaemonStarter.getDaemonName() + ".log");
				DaemonStarter.darofi.setDatePattern("'.'yyyy-MM-dd");
				DaemonStarter.darofi.setAppend(true);
				DaemonStarter.darofi.setThreshold(Level.INFO);
				DaemonStarter.darofi.activateOptions();
				DaemonStarter.rlog.addAppender(DaemonStarter.darofi);
				
				DaemonStarter.syslog = new SyslogAppender();
				DaemonStarter.syslog.setName("SYSLOG");
				DaemonStarter.syslog.setLayout(new PatternLayout(DaemonStarter.getDaemonName() + ": %-5p %c %x - %m%n"));
				DaemonStarter.syslog.setSyslogHost("localhost");
				DaemonStarter.syslog.setFacility("LOCAL0");
				DaemonStarter.syslog.setFacilityPrinting(false);
				DaemonStarter.syslog.setThreshold(Level.INFO);
				DaemonStarter.syslog.activateOptions();
				DaemonStarter.rlog.addAppender(DaemonStarter.syslog);
			}
			if (DaemonStarter.isDevelopmentMode() || DaemonStarter.isRunMode()) {
				// CONSOLE is only active in development and run mode
				DaemonStarter.console = new ConsoleAppender();
				DaemonStarter.console.setName("CONSOLE");
				DaemonStarter.console.setLayout(new PatternLayout("%d{HH:mm:ss,SSS} %-5p %c %x - %m%n"));
				DaemonStarter.console.setTarget(ConsoleAppender.SYSTEM_OUT);
				DaemonStarter.console.activateOptions();
				DaemonStarter.rlog.addAppender(DaemonStarter.console);
			}
			
		} catch (final Exception e) {
			System.err.println("Logger config failed with exception: " + e.getMessage());
			DaemonStarter.getLifecycleListener().exception(DaemonStarter.currentPhase.get(), e);
		}
	}
	
	private static void amendLogAppender() {
		final Level logLevel = Level.toLevel(DaemonStarter.daemonProperties.getProperty(DaemonProperties.LOGGER_LEVEL), Level.INFO);
		final String logPattern = System.getProperty(DaemonProperties.LOGGER_PATTERN, "%d{HH:mm:ss,SSS} %-5p %c %x - %m%n");
		DaemonStarter.rlog.setLevel(logLevel);
		DaemonStarter.rlog.info(String.format("Changed the the log level to %s", logLevel));
		
		if (!DaemonStarter.isDevelopmentMode()) {
			final String fileEnabled = DaemonStarter.daemonProperties.getProperty(DaemonProperties.LOGGER_FILE, "true");
			final String syslogEnabled = DaemonStarter.daemonProperties.getProperty(DaemonProperties.LOGGER_SYSLOG, "true");
			final String logglyEnabled = DaemonStarter.daemonProperties.getProperty(DaemonProperties.LOGGER_LOGGLY, "false");
			
			final String host = DaemonStarter.daemonProperties.getProperty(DaemonProperties.SYSLOG_HOST, "localhost");
			final String facility = DaemonStarter.daemonProperties.getProperty(DaemonProperties.SYSLOG_FACILITY, "LOCAL0");
			final Level syslogLevel = Level.toLevel(DaemonStarter.daemonProperties.getProperty(DaemonProperties.SYSLOG_LEVEL), Level.INFO);
			
			if ((fileEnabled != null) && fileEnabled.equals("false")) {
				DaemonStarter.rlog.removeAppender(DaemonStarter.darofi);
				DaemonStarter.darofi = null;
				DaemonStarter.rlog.info(String.format("Deactivated the FILE Appender"));
			} else {
				DaemonStarter.darofi.setThreshold(logLevel);
				DaemonStarter.darofi.setLayout(new PatternLayout(logPattern));
				DaemonStarter.darofi.activateOptions();
			}
			
			if ((syslogEnabled != null) && syslogEnabled.equals("false")) {
				DaemonStarter.rlog.removeAppender(DaemonStarter.syslog);
				DaemonStarter.syslog = null;
				DaemonStarter.rlog.info(String.format("Deactivated the SYSLOG Appender"));
			} else {
				DaemonStarter.syslog.setSyslogHost(host);
				DaemonStarter.syslog.setFacility(facility);
				DaemonStarter.syslog.setThreshold(syslogLevel);
				DaemonStarter.syslog.activateOptions();
				DaemonStarter.rlog.info(String.format("Changed the SYSLOG Appender to host %s and facility %s", host, facility));
			}
			
			if ((logglyEnabled != null) && logglyEnabled.equals("false")) {
				DaemonStarter.loggly = null;
				DaemonStarter.rlog.info(String.format("Deactivated the LOGGLY Appender"));
			} else {
				final String token = DaemonStarter.daemonProperties.getProperty(DaemonProperties.LOGGLY_TOKEN);
				if ((token == null) || token.isEmpty()) {
					DaemonStarter.rlog.error("Missing loggly token but loggly is activated");
				} else {
					final String tags = DaemonStarter.daemonProperties.getProperty(DaemonProperties.LOGGLY_TAGS);
					DaemonStarter.loggly = new LogglyAppender(token, tags);
					DaemonStarter.loggly.activateOptions();
					DaemonStarter.rlog.addAppender(DaemonStarter.loggly);
				}
			}
		}
		if (DaemonStarter.isDevelopmentMode() || DaemonStarter.isRunMode()) {
			DaemonStarter.console.setLayout(new PatternLayout(logPattern));
			DaemonStarter.console.setThreshold(logLevel);
			DaemonStarter.console.activateOptions();
		}
	}
	
	/**
	 * Stop the service and end the program
	 */
	public static void stopService() {
		DaemonStarter.currentPhase.set(LifecyclePhase.STOPPING);
		final CountDownLatch cdl = new CountDownLatch(1);
		Executors.newSingleThreadExecutor().execute(new Runnable() {
			
			@Override
			public void run() {
				DaemonStarter.getLifecycleListener().stopping();
				DaemonStarter.daemon.stop();
				cdl.countDown();
			}
		});
		
		try {
			int timeout = DaemonStarter.lifecycleListener.get().getShutdownTimeoutSeconds();
			if (!cdl.await(timeout, TimeUnit.SECONDS)) {
				DaemonStarter.rlog.error("Failed to stop gracefully");
				DaemonStarter.abortSystem();
			}
		} catch (InterruptedException e) {
			DaemonStarter.rlog.error("Failure awaiting stop", e);
		}
		
	}
	
	// I KNOW WHAT I AM DOING
	private final static void handleSignals() {
		if (DaemonStarter.isStartMode() || DaemonStarter.isRunMode()) {
			try {
				// handle SIGHUP to prevent process to get killed when exiting the tty
				Signal.handle(new Signal("HUP"), new SignalHandler() {
					
					@Override
					public void handle(final Signal arg0) {
						// Nothing to do here
						System.out.println("SIG INT");
					}
				});
			} catch (IllegalArgumentException e) {
				System.err.println("Signal HUP not supported");
			}
			
			try {
				// handle SIGTERM to notify the program to stop
				Signal.handle(new Signal("TERM"), new SignalHandler() {
					
					@Override
					public void handle(final Signal arg0) {
						System.out.println("SIG TERM");
						DaemonStarter.stopService();
					}
				});
			} catch (IllegalArgumentException e) {
				System.err.println("Signal TERM not supported");
			}
			
			try {
				// handle SIGINT to notify the program to stop
				Signal.handle(new Signal("INT"), new SignalHandler() {
					
					@Override
					public void handle(final Signal arg0) {
						System.out.println("SIG INT");
						DaemonStarter.stopService();
					}
				});
			} catch (IllegalArgumentException e) {
				System.err.println("Signal INT not supported");
			}
			
			try {
				// handle SIGUSR2 to notify the life-cycle listener
				Signal.handle(new Signal("USR2"), new SignalHandler() {
					
					@Override
					public void handle(final Signal arg0) {
						System.out.println("SIG USR2");
						DaemonStarter.getLifecycleListener().signalUSR2();
					}
				});
			} catch (IllegalArgumentException e) {
				System.err.println("Signal USR2 not supported");
			}
		}
	}
	
	/**
	 * Abort the daemon
	 */
	public static void abortSystem() {
		DaemonStarter.abortSystem(null);
	}
	
	/**
	 * Abort the daemon
	 *
	 * @param error the error causing the abortion
	 */
	public static void abortSystem(final Throwable error) {
		DaemonStarter.currentPhase.set(LifecyclePhase.ABORTING);
		try {
			DaemonStarter.getLifecycleListener().aborting();
		} catch (Exception e) {
			DaemonStarter.rlog.error("Custom abort failed", e);
		}
		if (error != null) {
			DaemonStarter.rlog.fatal("Unrecoverable error encountered  --> Exiting : " + error.getMessage());
			DaemonStarter.getLifecycleListener().exception(LifecyclePhase.ABORTING, error);
		} else {
			DaemonStarter.rlog.fatal("Unrecoverable error encountered --> Exiting");
		}
		// Exit system with failure return code
		System.exit(1);
	}
}