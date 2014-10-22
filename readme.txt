genericJMXAPI collector (prototype at this stage)

Extract the zip file in to a clean directory

Edit the configure.json file - you will need your premium email and api.key. You will need all the JMX information as well. 
	The original configure.json file contains field descriptions. Hopefully this will be sufficient.

Go to a terminal window and cd to the directory where genericJMXAPI.jar was extracted     
	
Run the collector with the following command:

	java -jar genericJMXAPI.jar configure.json

The collector will run, create the metrics and continue to run forever collecting metrics every interval that you defined in the configuration file.

Once you are happy that it is working well, set it up to run automatically with whatever you have available to do so e.g. cron.
