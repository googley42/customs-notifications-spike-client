# customs-notification-spike-client

A test harness for the round trip journey of sending and receiving notification payloads. It stands up endpoints
for the callback URL to receive the notifications. 
 
## instructions

Note you must have the following declarant callback data registered:
 
    TODO: 
 
To run you must have a CUSTOMS_DECLARATION_ALL service manager profile running 
 
    sm -f --start CUSTOMS_DECLARATION_ALL
    
To run 
    
    sbt run 
 
## start endpoint

This sends notifications

## stop endpoint

This compares sent notifications with received notifications

