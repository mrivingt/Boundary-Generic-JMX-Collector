import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.codec.binary.Base64;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Set;

import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

public class genericJMXAPI {

    static int logcount;
    static int Logwrapafter;
    static int ConTimeOut;
    static String Logfile;
    static String Stdout;

    public static void main(String[] args) throws Exception {

        genericJMXAPI cfg = new genericJMXAPI();

        String configFile = null;

        if (args.length != 0) {
            configFile = args[0];
            configFile = configFile.replace("\\", ""); // get rid of escape
            // characters
        } else {
            error("No configuration file specified. Filename must be specified as the only parameter.");
            System.exit(1);
        }

        System.out.println(new Date()
                           + " Process started - using configuration file: " + configFile);

        FileReader inputFile = null;
        try {
            inputFile = new FileReader(configFile);
        } catch (FileNotFoundException e) {
            error("Input file not found: " + configFile);
            System.exit(2);
        }

        JSONParser parser = new JSONParser();
        JSONObject configuration = null;

        try {
            configuration = (JSONObject) parser.parse(inputFile);
        } catch (ParseException e) {
            error("Input file has JSON parse error: " + e.getPosition() + " "
                  + e.toString());
            System.exit(4);
        }

        inputFile.close();
        
        // Start parsing the input JSON
        // There are many keys and a lot can go wrong with their definitions

        Logfile = (String) configuration.get("logfile"); // used in the log
        // method
        Stdout = (String) configuration.get("stdout");
        String wrap = (String) configuration.get("logwrapafter");

        if (wrap == null) {
            Logwrapafter = 100000;
        } else {
            Logwrapafter = Integer.parseInt(wrap);
        }
        
        String httptimeout = (String) configuration.get("httptimeout");

        if (httptimeout == null) {
            ConTimeOut = 5000;
        } else {
            ConTimeOut = Integer.parseInt(httptimeout) * 1000;  // Input is specified in seconds
        }

        String authString = (String) configuration.get("auth_string");
        if (authString == null) {
            error("No Boundary API authstring specified [auth_string]");
            System.exit(2);
        }

        if (!authString.contains("@") || !authString.contains("api.")) {
            log("@@@@@ Warning - auth_string from configuration file doesn't look right: "
                + authString);
        }

        byte[] authEncBytes = Base64.encodeBase64(authString.getBytes());
        String authStringEnc = new String(authEncBytes);

        String port = (String) configuration.get("port");
        if (port == null) {
            log("No port sepcified");
            System.exit(8);
        }

        String host = (String) configuration.get("host");
        if (host == null) {
            host = "localhost";
        }

        String user = (String) configuration.get("user");
        String password = (String) configuration.get("password");

        // Now we have enough data to attempt the JMX connection
        log("Attempting connection to JMX server on: " + host + " port: "
            + port);

        JMXServiceURL serviceURL = new JMXServiceURL(
            "service:jmx:rmi:///jndi/rmi://" + host + ":" + port
            + "/jmxrmi");

        // We may or may not have to connect with user and password
        // If the user and password keys were provided then we assume that we
        // have to authenticate

        JMXConnector jmxc = null;
        if (user == null) {
            jmxc = JMXConnectorFactory.connect(serviceURL);
        } else {
            Map<String, String[]> env = new HashMap<>();
            String[] credentials = { user, password };
            env.put(JMXConnector.CREDENTIALS, credentials);
            jmxc = JMXConnectorFactory.connect(serviceURL, env);
        }
        
        // Get an MBeanServerConnection
        //         

        MBeanServerConnection mbsc = jmxc.getMBeanServerConnection();
 
        // Continue to get the rest of the configuration data

        String interval = (String) configuration.get("interval");
        if (interval == null) {
            interval = "5";
        }
        
        String batchValue = (String) configuration.get("batch");
        if (batchValue == null) {
            batchValue = "5";
        }
        
        String definemetrics = (String) configuration.get("definemetrics");
        if (definemetrics == null) {
            definemetrics = "yes";
        }

        String source = (String) configuration.get("source");
        if (source == null) {

            // If the source was not specified in the input file, get the host
            // name
            try {
                InetAddress addr;
                addr = InetAddress.getLocalHost();
                source = addr.getHostName();
            } catch (UnknownHostException ex) {
                error("source not specified in configuration file and hostname can not be resolved");
                System.exit(16);
            }
        }

        // get each of the metric definitions from the json array called
        // "metrics" and process them one by one
        // This includes creating the metric in Boundary which can go wrong if
        // the connection is malformed or unavailable

        ArrayList<Metric> mbeans = new ArrayList<Metric>();

        String endpoint = "https://premium-api.boundary.com/v1/metrics/";
        log("Endpoint " + endpoint);

        JSONArray metrics = (JSONArray) configuration.get("metrics");
        for (Object o : metrics) {
            JSONObject config = (JSONObject) o;

            // Extract all the attributes and store them in the mbeans
            // collection after creating the metric in Boundary
            // This is a lot of processing that can fail for many reasons
            defineMetric(definemetrics, config, cfg, mbsc, mbeans, endpoint, authStringEnc);
        }

        // This is where we do the forever loop of the processing

        String url = "https://premium-api.boundary.com/v1/measurementsAsync";  // Async
        int postCounter = 0;
        
        while (true) { // Forever
            JSONArray metricsArray = new JSONArray();
        
            
            for (int batchCounter  = 0; batchCounter < Integer.valueOf(batchValue) ; batchCounter++) {
                long timethen = System.currentTimeMillis();
                long timeStamp = 0;
	            for (Object object : mbeans) {
	                Metric mbean = (Metric) object;
	
	                if (mbean.metric_type.equals("delta")) {
	                    if (!mbean.setDeltaValue()) {
	                        continue;
	                    } // for delta metrics there is no value on the first
	                    // request
	                } else {
	                    mbean.setCurrentValue();
	                }
	
	                JSONArray metricPayload = new JSONArray();
	                metricPayload.add(source);
	           //   metricPayload.add("test123");
	                metricPayload.add(mbean.boundary_metric_name);
	                metricPayload.add(mbean.displayValue);
	           //   metricPayload.add(String.valueOf(System.currentTimeMillis()));
	                timeStamp = System.currentTimeMillis() / 1000 ;
	                metricPayload.add(timeStamp);
	                metricsArray.add(metricPayload);
	            }
	
	         //   publishMetric(metricsArray, url, authStringEnc);
	         //   long timenow = System.currentTimeMillis();
	         //   long elapsed = timenow - timethen;
	        //    log("Time to get all mbeans and publish them was: " + elapsed
	        //        + " ms");
	            if (batchCounter == Integer.valueOf(batchValue) - 1) {
	            	postCounter++;
	               	log("Post counter ======> " + postCounter);
	            	publishMetric(metricsArray, url, authStringEnc);
	            }
	            long timenow = System.currentTimeMillis();
	            long elapsed = timenow - timethen;
	            
	            long sleepTime = 1000 * Integer.valueOf(interval) - elapsed - 2;
	            
	            log("Now we sleep for " + sleepTime + " Ms");
	
	            if (sleepTime > 0) {
	                Thread.sleep(sleepTime);
	            }
	            else {
	            	log("That last post took too long. The sleep time is negative: " + sleepTime);
	            }

        }
           
        }

    }

    private static void error(String msg) throws IOException {
        echo(msg);
        log(msg);
    }

    private static void echo(String msg) {
        System.out.print(msg);
    }

    private static void publishMetric(JSONArray payload, String url,
                                      String authStringEnc) throws IOException {

        String urlParameters = payload.toString();
        log("url parameters: " + urlParameters);

        int responseCode = 0;
        int retry = 0;
 do {
	    retry++;
 
        log("(" + retry + ") attempt to send to url: " + url);
        URL obj = new URL(url);
        HttpURLConnection con = (HttpURLConnection) obj.openConnection();

        con.setRequestProperty("Authorization", "Basic " + authStringEnc);

        con.setRequestMethod("POST");
        con.setRequestProperty("User-Agent", "Mozilla/5.0");
        con.setRequestProperty("Accept-Language", "en-US,en;q=0.5");
        con.setRequestProperty("Content-Type", "application/json");
        con.setDoOutput(true);
        con.setConnectTimeout(ConTimeOut); //milliseconds

        try {
        DataOutputStream wr = new DataOutputStream(con.getOutputStream());
 
        // Send request
        wr.writeBytes(urlParameters);
        wr.flush();
 
        responseCode = con.getResponseCode();
        log("Response Code : " + responseCode);

        wr.close();
        }
        catch(java.net.SocketTimeoutException e) {
        	log("Connection timeout");
        	//System.exit(32);
        }
        catch(java.net.ConnectException f) {
          	log("Connection exception");
        	//System.exit(32);
        }
 } while (retry < 6 && responseCode == 0);
	 
        if (responseCode != 200) {
            log("Something went wrong publishing the metric value: "
                + responseCode + "- skipping this one");

        }

    }

    private static void defineMetric(String definemetrics, JSONObject config, genericJMXAPI cfg,
                                     MBeanServerConnection mbsc, ArrayList<Metric> mbeans,
                                     String endpoint, String authStringEnc) throws IOException {

        String mbean_name = (String) config.get("mbean");
        mbean_name = mbean_name.replace("\\", ""); 					// get rid of escape
        //log("mbean_name: " + mbean_name);
        
        String attribute = (String) config.get("attribute");
        attribute = attribute.replace("\\", ""); 					// get rid of escape
        //log("attribute: " + attribute);
      
        String key = (String) config.get("key");
        //log("key: " + key);
        
        String metric_type = (String) config.get("metric_type");

        JSONObject metricDefinition = new JSONObject();

        String boundary_metric_name = (String) config
                                      .get("boundary_metric_name");
        String metric_description = (String) config.get("metric_description");
        String metric_displayName = (String) config.get("metric_displayName");
        String metric_displayNameShort = (String) config
                                         .get("metric_displayNameShort");
        String metric_unit = (String) config.get("metric_unit");
        String metric_defaultAggregate = (String) config
                                         .get("metric_defaultAggregate");
        String metric_defaultResolutionMS = (String) config
                                            .get("metric_defaultResolutionMS");

        if (metric_type == null) {
            metric_type = "standard";
        }

        // We have to upper case the boundary metric name since they "fixed" the API
        
        metricDefinition.put("name", boundary_metric_name.toUpperCase());
        metricDefinition.put("description", metric_description);
        metricDefinition.put("displayName", metric_displayName);
        metricDefinition.put("displayNameShort", metric_displayNameShort);
        metricDefinition.put("unit", metric_unit);
        metricDefinition.put("defaultAggregate", metric_defaultAggregate);
        metricDefinition.put("defaultResolutionMS", metric_defaultResolutionMS);

        if (mbean_name == null || attribute == null
                || boundary_metric_name == null || metric_description == null
                || metric_displayName == null
                || metric_displayNameShort == null || metric_unit == null
                || metric_defaultAggregate == null
                || metric_defaultResolutionMS == null) {

            log("***** Error in metrics definition");
            System.exit(1);
        }

        // Now we can create the metric in Boundary

        String urlParameters = null;
        int responseCode = 0;

        // We have to upper case the boundary metric name since they "fixed" the API
        String url = endpoint + boundary_metric_name.toUpperCase();
        log("url: " + url);
        int retry = 0;
 if (definemetrics.toLowerCase().equals("yes")) {                           
  do {
	    retry++;
	    
        URL obj = new URL(url);

        HttpURLConnection con = (HttpURLConnection) obj.openConnection();
        // log(" authString " + authStringEnc);
        con.setRequestProperty("Authorization", "Basic " + authStringEnc);

        con.setRequestMethod("PUT");
        con.setRequestProperty("User-Agent", "Mozilla/5.0");
        con.setRequestProperty("Accept-Language", "en-US,en;q=0.5");
        con.setRequestProperty("Content-Type", "application/json");
        con.setRequestProperty("Connection", "keep-alive");
        con.setDoOutput(true);
        con.setConnectTimeout(ConTimeOut); //milliseconds


        DataOutputStream wr = new DataOutputStream(con.getOutputStream());
 
        urlParameters = metricDefinition.toString();
        log("urlParameters: " + urlParameters);

        // Send request
        wr.writeBytes(urlParameters);
        wr.flush();

        responseCode = con.getResponseCode();
        log("Response Code : " + responseCode);
        
        // Read the error stream and log it if we did not get a 200
        if (responseCode != 200 ) {
            DataInputStream dis = new DataInputStream(con.getErrorStream());
            BufferedReader br =  new BufferedReader(new InputStreamReader(dis, "UTF-8"));
        	
        
        String s = "";
               
        while ((s = br.readLine()) != null) {
            log(s);
         }
        dis.close();
        }
        
        wr.close();
} while (responseCode == 502 && retry < 5);

        if (responseCode != 200) {
            log("Something went wrong creating the metric: " + responseCode);
            System.exit(3);
        }
    }
        // store the mbeans definitions away and store the MBean server
        // connection with them
        mbeans.add(cfg.new Metric(mbsc, mbean_name, attribute, key,
                                  boundary_metric_name, metric_type));

    }

    private static void log(String line) throws IOException {

        logcount++;
        long millis = System.currentTimeMillis();
        BufferedOutputStream bout = null;
        boolean appendflag = true;

        if (logcount % Logwrapafter == 0) {
            appendflag = false;
        }

        bout = new BufferedOutputStream(new FileOutputStream(Logfile,
                                        appendflag));
        line = new Date(millis) + " " + millis + " " + logcount + " " + line
        + System.getProperty("line.separator");

        if (Stdout.toLowerCase().equals("yes")) {
            echo(line);
        }

        bout.write(line.getBytes());
        bout.close();

    }

    private class Metric {
        String mbean_name;
        String attribute;
        String key;
        String boundary_metric_name;
        double currentValue;
        double lastValue;
        double displayValue;
        String metric_type;
        Boolean firstTime;
        MBeanServerConnection mbsc;
        String type; // as in int, long, double etc.

        Metric(MBeanServerConnection mbsc, String mbean_name, String attribute, String key,
               String boundary_metric_name, String metric_type) {
            this.mbean_name = mbean_name;
            this.attribute = attribute;
            this.key = key;
            this.boundary_metric_name = boundary_metric_name;
            this.metric_type = metric_type;
            this.firstTime = true;
            this.mbsc = mbsc;
            this.type = null;
        }

        void storeLastValue() {
            lastValue = currentValue;
        }

        Boolean setDeltaValue() throws MalformedObjectNameException,
            AttributeNotFoundException, InstanceNotFoundException,
            MBeanException, ReflectionException, IOException,
            IntrospectionException {
            this.setCurrentValue();
            if (firstTime) {
                this.storeLastValue();
                this.firstTime = false;
                return false;
            } else {
                displayValue = this.currentValue - this.lastValue;
                this.storeLastValue();
                return true;
            }

        }

        void setCurrentValue() throws MalformedObjectNameException,
            IOException, AttributeNotFoundException,
            InstanceNotFoundException, MBeanException, ReflectionException,
            IntrospectionException {
            ObjectName myMbeanName = new ObjectName(mbean_name);
            
            log("Getting value of MBean: " + myMbeanName);

            Set<ObjectInstance> mbeans = mbsc.queryMBeans(myMbeanName, null);
            log("Got value of MBean: " + myMbeanName);
            
            // This
            // should
            // only
            // return
            // 1
            // instance
            if (!mbeans.isEmpty()) {

                for (ObjectInstance name : mbeans) {

                    if (type == null) {
                        log("Check type of " + mbean_name + " " + attribute);
                        MBeanInfo beano = mbsc.getMBeanInfo(name
                                                            .getObjectName());
                        MBeanAttributeInfo[] infos = beano.getAttributes();

                        for (MBeanAttributeInfo info : infos) {
                            if (info.getName().equals(attribute)) {

                                type = info.getType().toString();

                                if (type.equals("int") || type.equals("long")
                                        || type.equals("java.lang.Object")
                                        || type.equals("javax.management.openmbean.CompositeData")
                                        || type.equals("double")
                                        || type.equals("float")) {
                                    log(attribute + " is type: " + type);
                                } else {
                                    log("@@@@@ "
                                        + attribute
                                        + " is type: "
                                        + type
                                        + " but we do not know what to do with this type.");
                                }
                                break;
                            }
                        }
                    }

                    
                    /* This is example of getting composite data
                     * 
                     * CompositeDataSupport heapMemory=(CompositeDataSupport)mbeanServer.getAttribute(memoryName,"HeapMemoryUsage");
  						long usedHeapMemory=(Long)heapMemory.get("used");
  						long committedHeapMemory=(Long)heapMemory.get("committed");
  						long maxHeapMemory=(Long)heapMemory.get("max");
                     */
                    
                    
                    switch (type) {
                    case "int":
                        currentValue = (int) mbsc.getAttribute(
                                           name.getObjectName(), attribute);
                        break;
                    case "long":
                        currentValue = (long) mbsc.getAttribute(
                                           name.getObjectName(), attribute);
                        break;
                    case "double":
                        currentValue = (double) mbsc.getAttribute(
                                           name.getObjectName(), attribute);
                        break;
                    case "float":
                        currentValue = (float) mbsc.getAttribute(
                                           name.getObjectName(), attribute);
                        break;
                    case "java.lang.Object":
                        currentValue = (double) mbsc.getAttribute(
                                           name.getObjectName(), attribute);
                        break;
                    case "javax.management.openmbean.CompositeData":
                    	CompositeDataSupport cData=(CompositeDataSupport)mbsc.getAttribute(name.getObjectName(),attribute);
                    	currentValue = (long) cData.get(key);
                        break;
                    default:
                        log("@@@@@ Unknown type: " + type
                            + " - unable to get attribute: " + attribute);
                    }

                    log("Metric current value for " + mbean_name + " "
                        + attribute + " " + currentValue);

                }

            } else {
                log("Unable to locate MBean so skipping it: " + mbean_name);
            }

            displayValue = currentValue;

        }

    }

}