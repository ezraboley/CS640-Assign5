# Built by Dillon O'Leary and Ezra Boley

PY := "python3"		# Pretty sure we want python3 (because python2.7 is dumb)
PORT := 7777
HOST := "localhost"

.PHONY: testPy clientServer cleanUp

# This will first attempt to cleanup a previous run, and then run the test
# server/client.
testPy: cleanUp clientServer

clientServer:
	@echo "Type in text to send with python client!"
	$(PY) fc/server.py -p $(PORT) -l .5 &
	$(PY) fc/client.py -p $(PORT) -h $(HOST) -l .5

# You can run this if the port hasn't been given up
cleanUp:
	@echo "Ending the server"
	-pkill python3


