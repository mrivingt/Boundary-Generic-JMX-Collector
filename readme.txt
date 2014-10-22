Boundary-Generic-JMX-Collector
==============================

Generic JMX MBean collector that uses the Boundary API to create metrics and publish them.

This java application reads a configuration file the name of which is passed as a parameter. 
The configuration file is a JSON structure that defines the JMX connection, the MBean attributes to collect and 
also the Boundary metric definitions. It requires a Boundary api string <email:api-key> to be specified.

A Boundary premium account is required as well as a working knowledge of JMX and an understanding of the Boundary metrics API:

http://premium-documentation.boundary.com/v1/put/metrics/:metric



Instructions for use
=====================

Edit the configure.json file - you will need your Boundary email and api.key. You will need all the JMX information as well. 
The original configure.json file contains field descriptions. Hopefully this will be sufficient. 

Go to a terminal window and cd to the directory where genericJMXAPI.jar and the configuration file are.     
	
Run the collector with the following command:

	java -jar genericJMXAPI.jar configure.json

The collector will run, create the metrics and continue to run forever collecting metrics every interval that 
you defined in the configuration file.

Once you are happy that it is working well, set it up to run automatically with whatever you have available to do so e.g. cron.
