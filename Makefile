# Built by Dillon O'Leary and Ezra Boley

PY := "python3"		# Pretty sure we want python3 (because python2.7 is dumb)
PORT := 7777
HOST := "localhost"
LOSS := 0.1

.PHONY: testPy clientServer cleanUp

# This will first attempt to cleanup a previous run, and then run the test
# server/client.
build:
	javac -d ./ src/edu/wisc/cs/sdn/simpledns/SimpleDNS.java src/edu/wisc/cs/sdn/simpledns/packet/*.java

run:
	java edu/wisc/cs/sdn/simpledns/SimpleDNS -r l.root-servers.net -e ec2.csv

clean:
	rm -r edu

testPy: cleanUp clientServer

clientServer:
	@echo "Type in text to send with python client!"
	$(PY) fc/server.py -p $(PORT) -l .5 & 
	cat fc/swp.py | $(PY) fc/client.py -p $(PORT) -h $(HOST) -l $(LOSS)

client:
	cat fc/swp.py | $(PY) fc/client.py -p $(PORT) -h $(HOST) -l $(LOSS)

server:
	$(PY) fc/server.py -p $(PORT) -l $(LOSS) 1> output.txt

# You can run this if the port hasn't been given up
cleanUp:
	@echo "Ending the server"
	-pkill python3


