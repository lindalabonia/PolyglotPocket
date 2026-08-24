"""One-time LOCAL tool: obtain a Gmail API refresh token for the sender mailbox.

Run this on your own machine, NOT on PythonAnywhere.

Prerequisites (Google Cloud Console, same project as Cloud Vision):
  1. Enable the "Gmail API".
  2. Create an OAuth client of type "Desktop app" and download its JSON
     (usually named client_secret_XXX.json).
  3. Add your sender Gmail address as a Test user on the OAuth consent screen.

Setup:  pip install google-auth-oauthlib
Usage:  python get_gmail_refresh_token.py client_secret.json

It opens a browser, you sign in with the SENDER mailbox and grant "send email",
then it prints the values to paste into the PythonAnywhere WSGI env.
"""
import sys

from google_auth_oauthlib.flow import InstalledAppFlow

SCOPES = ["https://www.googleapis.com/auth/gmail.send"]


def main():
    if len(sys.argv) != 2:
        print("usage: python get_gmail_refresh_token.py <client_secret.json>")
        return

    flow = InstalledAppFlow.from_client_secrets_file(sys.argv[1], SCOPES)
    # access_type=offline + prompt=consent force a refresh token to be returned.
    creds = flow.run_local_server(port=0, access_type="offline", prompt="consent")

    print("\n--- paste these into your WSGI env ---")
    print(f'os.environ["GMAIL_CLIENT_ID"] = "{creds.client_id}"')
    print(f'os.environ["GMAIL_CLIENT_SECRET"] = "{creds.client_secret}"')
    print(f'os.environ["GMAIL_REFRESH_TOKEN"] = "{creds.refresh_token}"')
    print('os.environ["GMAIL_SENDER"] = "<the Gmail address you just authorized>"')


if __name__ == "__main__":
    main()
