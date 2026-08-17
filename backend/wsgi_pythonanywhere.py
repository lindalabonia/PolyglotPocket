# Contenuto di ESEMPIO per il file WSGI di PythonAnywhere.
# NON caricare questo file: copia queste righe dentro il file WSGI che PA
# genera automaticamente (Web tab -> link "WSGI configuration file"),
# CANCELLANDO il contenuto di esempio che PA ci mette di default.
#
# Sostituisci 'tuonome' con il tuo username PythonAnywhere e assicurati che il
# percorso punti alla cartella dove hai caricato app.py e polyglot.db.

import sys

path = "/home/tuonome/mysite"      # <-- cartella con app.py e polyglot.db
if path not in sys.path:
    sys.path.insert(0, path)

from app import app as application  # noqa: E402   (Flask app -> 'application')
