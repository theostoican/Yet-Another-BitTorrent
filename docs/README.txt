README - Tema3 - SPRC

Theodor STOICAN
341C3

In cadrul temei, am decis sa implementez urmatoarele clase:

* Server
* Client
* PeersServer
* DownloadTask
* UploadTask
* SendTask

Voi descrie in cele de mai jos fiecare dintre aceste clase, cat si alte detalii
relevante de implementare. La nivelul serverului am adoptat o abordare asincrona,
pornind noi thread-uri de fiecare data cand se da un raspuns la nivel de server.
Pentru comunicatie asincrona, pastrez niste structuri de date de tip Map la
nivelul serverului, pentru a tine minte daca am primit tot pachetul de la un
anumit client sau mai am inca de primit. Dpdv al implementarii, am folosit SocketChannel
si SelectionKey pentru multiplexare. 

Conceptual, am 3 tipuri de request-uri pe care un client le face la server:
*update -> dupa ce un client downloadeaza un fisier, face un request pentru a fi
adaugat la lista de peers
*download -> un client face o cerere pentru a downloada un fisier
*publish -> un client face o cerere pentru a publica fisiere

Tinand cont de cele de mai sus, mi-am creat un protocol pentru a comunica cu serverul.
Un payload pentru publish arata in felul urmator: p<port_number><size_fileDescription>
	<fileDescription>.

Fiecare payload, in functie de operatia pe care vrea sa o execute un client, incepe cu
prima litera corespunzatoare operatiei. In cazul publish, aceasta este 'p', dupa care
port-ul pe care asculta clientul la download-uri, dimensiunea unei structuri FileDescription,
iar apoi structura in sine. Structura implementeaza clasa Serializable, pentru a putea fi
trimisa pe retea.

La nivelul clientului, in functia retrieveFile, folosesc un thread pool pentru a spawna
thread-uri pentru a downloada fragmente pentru un anumit fisier. Clientii sunt toleranti
la defecte (daca pica un peer, atunci ma asigur ca refac conexiunea si incerc sa downloadez
de la alt peer). In publishFile, doar trimit un payload de tip publish cu fileDescription-ul 
aferent.

De asemenea, folosesc o clasa numita PeersServer, pentru a servi, la nivelul client-ului,
fragmente dintr-un fisier celorlalti peers. De fiecare data cand primesc o cerere de
download din partea uni client, submit in thread pool mai multe instante ale UploadTask.
In momentul in care selectez un peer de la care sa downloadez un fragment, aleg in mod
aleator acest peer si testez conexiunea sa vad daca functioneaza. Daca nu, aleg alt peer
pana cand imi trag tot fragmentul din acel fisier. Fisierele sunt salvate, la nivelul
clientului, folosind numele fisierului, dar precedat de textul "Client"<port>_<filename>,
pentru a putea face distinctia intre fisierele mai multor clienti, in cazul in care se
salveaza in acelasi director.

In ceea ce priveste sincronizarea intre thread-uri, folosesc synchronized pe map-uri
la nivelul unor obiecte care reprezinta structuri de date partajate de mai multe threaduri.

Cam asta ar fi, per total, descrierea temei. A fost o tema decenta din care am invatat
cateva lucruri noi :).

