# Yet-Another-BitTorrent

Scalable peer-to-peer application for downloading files from multiple sources.

The architecture consists of a central node (the server) and an arbitrary number of peers (the clients). Clients may come and go asynchronously (without any notification). The app scales with the number of clients and files transferred.

# Requirements:
log4j

# Usage:
ant build

ant run-server

ant run-client1

ant run-client2
