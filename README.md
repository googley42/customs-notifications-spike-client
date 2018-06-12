# customs-notification-spike-client

A test harness for the round trip journey of sending and receiving notification payloads. It stands up endpoints
for the callback URL to receive the notifications. 
 
## instructions

Note you must have the following declarant callback data registered (you need to replace <SOME_UUID_1> and <SOME_UUID_2> with real UUID values):
 
    curl -v -X PUT "http://localhost:9650/field/application/<SOME_UUID_1>/context/customs%2Fdeclarations/version/2.0" -H "Cache-Control: no-cache" -H "Content-Type: application/json" -d '{ "fields" : { "callbackUrl" : "http://localhost:9000/clientACallback", "securityToken" : "securityToken1" } }'
    curl -v -X PUT "http://localhost:9650/field/application/<SOME_UUID_2>/context/customs%2Fdeclarations/version/2.0" -H "Cache-Control: no-cache" -H "Content-Type: application/json" -d '{ "fields" : { "callbackUrl" : "http://localhost:9000/clientBCallback", "securityToken" : "securityToken2" } }'

Using a mongo query tool eg Robo3t get the fieldsIds for the above entries and paste these into `appliaction.conf` eg: 

    clientASubscriptionId = "fcff927b-11d4-41e9-87b1-bec27a275a40"
    clientBSubscriptionId = "db86f737-841b-4904-ac7b-31bbac45280a"

 
To run you must have a CUSTOMS_DECLARATION_ALL service manager profile running 
 
    sm -f --start CUSTOMS_DECLARATION_ALL
    
To run 
    
    sbt run 
 
## start endpoint

    POST        /start                  @uk.gov.hmrc.customs.notification.spike.controllers.Start.start

This sends notifications

## end endpoint

    GET         /end                    @uk.gov.hmrc.customs.notification.spike.controllers.Start.end

This compares sent notifications with received notifications

