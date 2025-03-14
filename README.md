# IPWatcher

A simple dyndns client that reacts to linux ipv6 update events monitored via `ip -6 monitor address`. With IPv6 usually all devices receive public global ip addresses and in typical home networks a dynamic prefix is announced by the home router whenever that public prefix is changed by the internet service provider. Domains for devices behind the home router shoud point directly to the IPv6 address of that divice, not to the router as for IPv4. Home routers often lack the flexibility do update dyndns services with IPv6 addresses for different devices. This client can run on the target device and update the dyndns service with its IPv6 address whenever the router announces a changed prefix. Optionally, the public IPv4 address from the router can be determined using UPnP. IPv4 changes are however not detected, the IPv4 address is assumed to change at the same time as the IPv6 prefix.

Updates are supported via GET requests to a uri (with optinal HTTP basic authentification) or via cloudflare api.

## Compilation
The application can be assembled as a standalone JAR with
```
mill assembly
```

## Usage
Simple example to update three dyndns services, dedyn.io with IPv4 and IPv6 and duckdns.org and cloudflare only with IPv6.

```
java -jar /opt/ipwatcher/ipwatcher.jar --dyndns "uri --user myservice.example.com --password 12345 --uri https://update.dedyn.io/?myipv4=(IP4)&myipv6=(IP6)" --dyndns "uri --uriIp6 https://www.duckdns.org/update?domains=otherservice.example.com&token=123&uriIp6=(IP6)" --dyndns "cloudflare --token $apitoken --zone $zoneid --record $recordid --ip6"
```

See the command line help for all available options.

## Hints

* The command line options are nested, `--dyndns` takes a string of an other command (uri or cloudflare) and respective options to define a single dyndns service.
* Authentication data is only supported via HTTP basic authentication for uri services.
* Use --uriIp6 if a service does not need an IPv4 address. Both can be specified to send a request without an IPv4 address if none is detected.
* If no service requires an IPv4 address no UPnP requests are made.
* Updates are sent in parallel, if a dyndns service cannot handle parallel requests you can put them into a request group.
* For cloudflare, PATCH requests are used, updates will only change the IP of a dns record.
* Record ids for cloudflare have to be queried via cloudflare api, there is no UI to get them.
