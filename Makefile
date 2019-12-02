# Built by Dillon O'Leary and Ezra Boley

PY := "python3"		# Pretty sure we want python3 (because python2.7 is dumb)
PORT := 7777
HOST := "localhost"

.PHONY: testPy

testPy:
	@echo "Type in text to send with python client!"
	$(PY) fc/client.py -p $(PORT) -h $(HOST)

