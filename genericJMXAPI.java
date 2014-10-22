
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
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
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;


public class genericJMXAPI {

	static int logcount;
	static String Logfile;
	static String Stdout;
	
	public static void main(String[] args) throws Exception {
		
		genericJMXAPI cfg = new genericJMXAPI(); 
		
		String configFile = null;
			
		if (args.length != 0) {
			configFile = args[0];
			configFile = configFile.replace("\\",""); // get rid of escape characters
		}
		else {
			error("No configuration file specified. Filename must be specified as the only parameter.");
			System.exit(1);
		}
	
		System.out.println(new Date() + " Process started - using configuration file: " + configFile);
	
		FileReader inputFile = null;	
		try {
			inputFile = new FileReader(configFile);
		}
		catch (FileNotFoundException e) {
			error("Input file not found: " + configFile);
			System.exit(2);
		}

		
	    JSONParser parser = new JSONParser();
		JSONObject configuration = null;
		
		try {
		configuration = (JSONObject) parser.parse(inputFile);
		}
		catch (ParseException e) {
			error("Input file has JSON parse error: " + e.getPosition() + " " + e.toString());
			System.exit(4);
		}

		inputFile.close(); 
		
		Logfile = (String) configuration.get("logfile");   // used in the log method
		Stdout = (String) configuration.get("stdout");
		
		
		// Start parsing the input JSON
		// There are many keys and a lot can go wrong with their definitions
		
	 	String authString = (String) configuration.get("auth_string");
	 	if (authString == null) {
	 		  error("No Boundary API authstring specified [auth_string]");
	 		  System.exit(2);
	 	}
	 	
	 	
	 	if (!authString.contains("@") || !authString.contains("api.") ) {
	 		log("@@@@@ Warning - auth_string from configuration file doesn't look right: " + authString);
	 	}
		
	 	byte[] authEncBytes = Base64.encodeBase64(authString.getBytes());
	  	String authStringEnc = new String(authEncBytes);
	    
	  	String port = (String) configuration.get("port");
	    if (port == null) {log("No port sepcified"); System.exit(8);}
	    
	    String host = (String) configuration.get("host");
	    if (host == null) {host = "localhost";}
	    
	    String user = (String) configuration.get("user");
 	    String password = (String) configuration.get("password");
		    
	    
		// Now we have enough data to attempt the JMX connection
		  
		log("Attempting connection to: " + host + " port: "+ port);
	      
		JMXServiceURL serviceURL = new JMXServiceURL(
			                "service:jmx:rmi:///jndi/rmi://" + host + ":" + port + "/jmxrmi");

		// We may or may not have to connect with user and password
		// If the user and password keys were provided then we assume that we have to authenticate
		//
		JMXConnector jmxc = null;
		if (user == null) {
			jmxc = JMXConnectorFactory.connect(serviceURL); 
		}
		else {
			Map<String, String[]> env = new HashMap<>();
			String[] credentials = {user, password};
			env.put(JMXConnector.CREDENTIALS, credentials);
			jmxc = JMXConnectorFactory.connect(serviceURL, env);
		}

        MBeanServerConnection mbsc = jmxc.getMBeanServerConnection();
        
        // Continue to get the rest of the configuration data
	    
	    String interval = (String) configuration.get("interval");
	    if (interval == null) {interval = "5";}

	    String source = (String) configuration.get("source");
	    if (source == null) {

	    // If the source was not specified in the input file, get the host name
	   	try
	    	{
	    	    InetAddress addr;
	    	    addr = InetAddress.getLocalHost();
	    	    source = addr.getHostName();
	    	}
	    	catch (UnknownHostException ex)
	    	{
	    	    error("source not specified in configuration file and hostname can not be resolved");
	    	    System.exit(16);
	    	}
	    }  	
	    
	
	    // get each of the metric definitions from the json array called "metrics" and process them one by one
	    // This includes creating the metric in Boundary which can go wrong if the connection is malformed or unavailable

		ArrayList<Metric> mbeans = new ArrayList<Metric>();
		
		  String endpoint = "https://premium-api.boundary.com/v1/metrics/";
		  log("Endpoint " + endpoint);
    
		JSONArray metrics = (JSONArray) configuration.get("metrics");
		  for (Object o : metrics)
		  {
		    JSONObject config = (JSONObject) o;
		    
		 // Extract all the attributes and store them in the mbeans collection after creating the metric in Boundary
		 // This is a lot of processing that can fail for many reasons
		    defineMetric(config, cfg, mbsc, mbeans, endpoint , authStringEnc);    
	    
		  }
	
		 // This is where we do the forever loop of the plugin processing
 
		  String url = "https://premium-api.boundary.com/v1/measurements";
		
	      while (true) {   // Forever
	  

		     JSONArray metricsArray = new JSONArray();
		      
             long timethen = System.currentTimeMillis();
             for(Object object : mbeans) {
		  		 Metric mbean = (Metric) object;
  		         if (mbean.metric_type.equals("delta")) {
  		        	if (!mbean.setDeltaValue()) {continue;}   // for delta metrics there is no value on the first request
  		         }
  		         else {mbean.setCurrentValue();}
  		    	 JSONArray metricPayload = new JSONArray();
   			      metricPayload.add(source);
  			  	  metricPayload.add(mbean.boundary_metric_name);
  	 		  	  metricPayload.add(mbean.displayValue);
  			  	  metricPayload.add(String.valueOf(System.currentTimeMillis()));
  			  	metricsArray.add(metricPayload);
	
    		}
	       
	        
		    publishMetric(metricsArray, url, authStringEnc);  // This is for Boundary Meter
            long timenow = System.currentTimeMillis();	
            long elapsed = timenow - timethen;
            log("Time to get all mbeans and publish them was: " + elapsed + " ms");

 	        Thread.sleep(1000 * Integer.valueOf(interval) - elapsed -1);    
	      }

		// never ends         
	   }
		    
  private static void error(String msg) throws IOException {
	echo(msg);
	log(msg);
  }
  
  private static void echo(String msg) {
       System.out.print(msg);
  }
  
  private static void publishMetric(JSONArray payload, String url, String authStringEnc) throws IOException {
	
	  	  String   urlParameters = payload.toString();
	      log("url parameters: " + urlParameters);
	    
	  	  int responseCode = 0;
	  	  
		  log("url: " + url);
	  	  URL obj = new URL(url);
	  	  HttpURLConnection con = (HttpURLConnection) obj.openConnection();
	  	  
	  	  con.setRequestProperty("Authorization", "Basic " + authStringEnc);
	  	      
	  	  con.setRequestMethod("POST");
	  	  con.setRequestProperty("User-Agent", "Mozilla/5.0");
	  	  con.setRequestProperty("Accept-Language", "en-US,en;q=0.5");
	  	  con.setRequestProperty("Content-Type" , "application/json" );
	  	  con.setDoOutput(true);
	        	  
  		  DataOutputStream wr = new DataOutputStream(con.getOutputStream());
	   	   	

	  	  // Send request
	  	  wr.writeBytes(urlParameters);
	  	  wr.flush();
	  	
	  	  responseCode = con.getResponseCode();
	  	  log("Response Code : " + responseCode);
	  		 
	  	  wr.close();
	    
	    if (responseCode != 200 ) {
	    	log("Something went wrong publishing the metric value: " + responseCode + "- skipping this one");
	    	
	    }
	  
  }
 
private static void defineMetric(JSONObject config, genericJMXAPI cfg, MBeanServerConnection mbsc, 
		  ArrayList<Metric> mbeans, String endpoint, String authStringEnc) throws IOException {
	  
	    
	    String mbean_name = (String) config.get("mbean");
	    String attribute = (String) config.get("attribute");
	    String metric_type = (String) config.get("metric_type");
	    
        JSONObject metricDefinition = new JSONObject();
      
	    String boundary_metric_name = (String) config.get("boundary_metric_name");
	    String metric_description = (String) config.get("metric_description");
		String metric_displayName = (String) config.get("metric_displayName");
	    String metric_displayNameShort = (String) config.get("metric_displayNameShort");
	    String metric_unit = (String) config.get("metric_unit");
	    String metric_defaultAggregate = (String) config.get("metric_defaultAggregate");
	    String metric_defaultResolutionMS = (String) config.get("metric_defaultResolutionMS");
	    
	    if (metric_type == null) {
	    	metric_type = "standard";
	    }
	    
   
	      metricDefinition.put("name",boundary_metric_name);
  		  metricDefinition.put("description",metric_description);
  		  metricDefinition.put("displayName",metric_displayName);
  		  metricDefinition.put("displayNameShort",metric_displayNameShort);
  		  metricDefinition.put("unit",metric_unit);
  		  metricDefinition.put("defaultAggregate", metric_defaultAggregate);
  		  metricDefinition.put("defaultResolutionMS", metric_defaultResolutionMS);
  		  
  		
  	    if (mbean_name == null || attribute == null || boundary_metric_name == null || metric_description == null ||
	    		metric_displayName == null || metric_displayNameShort == null || metric_unit == null || metric_defaultAggregate == null ||
	    		metric_defaultResolutionMS == null)  {		    	
	 
	       	log("***** Error in metrics definition");
	    	System.exit(1);
	    }
  		  
   	  
     // Now we can create the metric in Boundary  

   	  String urlParameters = null;
  	  int responseCode = 0;
  	  

  	      	  
      String url = endpoint + boundary_metric_name;
      log("url: " + url);
  	  
  	  URL obj = new URL(url);
  	  
  	
  	  HttpURLConnection con = (HttpURLConnection) obj.openConnection();
  	  // log(" authString " + authStringEnc);
  	  con.setRequestProperty("Authorization", "Basic " + authStringEnc);
  	      
  	  con.setRequestMethod("PUT");
  	  con.setRequestProperty("User-Agent", "Mozilla/5.0");
  	  con.setRequestProperty("Accept-Language", "en-US,en;q=0.5");
  	  con.setRequestProperty("Content-Type" , "application/json" );
  	  con.setRequestProperty("Connection", "keep-alive");
  	  con.setDoOutput(true);

  	  		DataOutputStream wr = new DataOutputStream(con.getOutputStream());
   	   	
  	  		urlParameters  = metricDefinition.toString();
    		log("urlParameters: " + urlParameters);
   
	  		// Send request
	  		 wr.writeBytes(urlParameters);
	  		 wr.flush();
	  	
	  		 responseCode = con.getResponseCode();
	  		 log("Response Code : " + responseCode);
	  		 
	  		 wr.close();
  	    
  	    if (responseCode != 200 ) {
  	    	log("Something went wrong creating the metric: " + responseCode);
  	    	System.exit(3);
  	    }
    
	    // store the mbeans definitions away and store the MBean server connection with them
	    mbeans.add( cfg.new Metric(mbsc, mbean_name, attribute, boundary_metric_name, metric_type));  
  
}
  
  private static void log(String line) throws IOException {

	  logcount++;
	  long millis = System.currentTimeMillis() ;
	  
	  BufferedOutputStream bout = null;
	  boolean appendflag = true;
	  if (logcount%1000 == 0) {appendflag = false;}
      bout = new BufferedOutputStream( new FileOutputStream(Logfile,appendflag) );
	  line = new Date(millis) + " " + millis + " " + logcount + " " 
			  + line + System.getProperty("line.separator");
	  if (Stdout.toLowerCase().equals("yes"))  {echo(line);}
	  bout.write(line.getBytes());
	  bout.close();
   
  }


	private class Metric {
		String mbean_name;
		String attribute;
		String boundary_metric_name;
		double currentValue;
		double lastValue;
		double displayValue;
		String metric_type;
		Boolean firstTime;
		MBeanServerConnection mbsc;
		String type; // as in int, long, double etc.
		
		Metric(MBeanServerConnection mbsc, String mbean_name, String attribute, 
				String boundary_metric_name, String metric_type) {
			this.mbean_name = mbean_name;
			this.attribute = attribute;
			this.boundary_metric_name = boundary_metric_name;
			this.metric_type = metric_type;
			this.firstTime = true;
			this.mbsc = mbsc;
			this.type = null;
		}
		
		void storeLastValue() {
			lastValue = currentValue;
		}
		
		Boolean setDeltaValue() throws MalformedObjectNameException, AttributeNotFoundException, InstanceNotFoundException, MBeanException, ReflectionException, IOException, IntrospectionException {
			this.setCurrentValue();
			if (firstTime) {
				this.storeLastValue();
				this.firstTime = false;
				return false;
			}
			else {
				displayValue = this.currentValue - this.lastValue;
				this.storeLastValue();
				return true;
			}
			
		}
		void setCurrentValue() throws MalformedObjectNameException, IOException, AttributeNotFoundException, InstanceNotFoundException, MBeanException, ReflectionException, IntrospectionException {
		   	ObjectName myMbeanName = new ObjectName(mbean_name);
	       	
	       	Set<ObjectInstance> mbeans  = this.mbsc.queryMBeans(myMbeanName,null);    // This should only return 1 instance
	       	if (!mbeans.isEmpty()) {
	  
		   	 for (ObjectInstance name : mbeans) {
		   		 
		   		 
		   		if (this.type == null) { 
		   			log("Check type of " + mbean_name + " " + attribute);
		   			MBeanInfo beano = this.mbsc.getMBeanInfo(name.getObjectName());
		   			MBeanAttributeInfo[] infos = beano.getAttributes();
		   			   		
		   			for(MBeanAttributeInfo info : infos) {
			   		    if(info.getName().equals(attribute)) {
			   		        
			   		        this.type = info.getType().toString();

			   		        
			   		        if (this.type.equals("int") || this.type.equals("long") || this.type.equals("java.lang.Object") || this.type.equals("double")
			   		        		|| this.type.equals("float")) {
				   		        log(attribute + " is type: " + this.type) ;	
			   		        }
			   		        else {
				   		        log("@@@@@ " + attribute + " is type: " + this.type + " but we do not know what to do with this type.") ;
			   		        }
			   		        break;
			   		    }
		   			}
		   		}
		   		
		   		
		   		switch (this.type) {
			   		case "int" : currentValue = (int) this.mbsc.getAttribute( name.getObjectName(), attribute);
			   			break;
			   		case "long" : currentValue = (long) this.mbsc.getAttribute( name.getObjectName(), attribute);
		   				break;
			   		case "double" : currentValue = (double) this.mbsc.getAttribute( name.getObjectName(), attribute);
	   					break;
			   		case "float" : currentValue = (float) this.mbsc.getAttribute( name.getObjectName(), attribute);
   						break;
			   		case "java.lang.Object" : currentValue = (double) this.mbsc.getAttribute( name.getObjectName(), attribute);
		   				break;
		   			default: log ("@@@@@ Unknown type: " + this.type + " - unable to get attribute: " + attribute);	
		   		}

	
		    	log("Metric current value for " + mbean_name + " " + attribute + " " + currentValue);
		    		    		
		     }
		            	
		  	}
		  	else { error("Unable to locate MBean: "+ mbean_name);}
	       	
	       	displayValue = currentValue;
		
		}
	
	

}


}