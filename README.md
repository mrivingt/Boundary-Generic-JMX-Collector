Boundary-Generic-JMX-Collector
==============================

Generic JMX MBean collector that uses the Boundary API to create metrics and publish them.

This java application reads a configuration file the name of which is passed as a parameter. The configuration file is a JSON structure that defines the JMX connection, the MBean attributes to collect and also the Boundary metric definitions. It requires a Boundary api string <email:api-key> to be specified. A Boundary premium account is required.
