# Engineering Study AI — GitHub / Phone APK

This is a phone-installable personal-use Android app for studying **Design of Highway Pavements**. The 74-page PDF is included in the app assets, so the GitHub project stays below GitHub's 25 MB per-file browser upload limit.

## What it does
- Native Android wrapper with a mobile study UI
- OpenAI Responses API + File Search
- Uploads the selected PDF to your OpenAI account and creates a private vector store
- Explain, exam answer, numerical solution, quiz, flashcards, viva, summary and compare modes
- Lets you use the included 74-page PDF or choose another PDF from the phone
- API key is stored locally on the device for personal use

## Build directly from GitHub on your phone
1. Create a new GitHub repository.
2. Extract this ZIP on your phone.
3. In GitHub, upload the **contents of the `EngineeringStudyAI_Phone` folder** (not the outer ZIP).
4. Make sure `.github/workflows/build-apk.yml` is included.
5. Open **Actions → Build Engineering Study AI APK → Run workflow**.
6. Wait for the workflow to finish.
7. Open the completed workflow run → **Artifacts** → download `EngineeringStudyAI-debug-apk`.
8. Extract the artifact and install `app-debug.apk` on Android.

If GitHub's mobile upload interface does not preserve the `.github` folder, use GitHub's web editor or desktop/browser upload to ensure the workflow file is present.

## First use
1. Open the app.
2. Enter your OpenAI API key and tap **Save key**.
3. Tap **Use included PDF**.
4. Tap **Prepare AI Notes** and wait for indexing to complete.
5. Ask a question in AI Tutor.

## Security
This is the personal-use Option A architecture. The API key is stored locally and the app calls OpenAI directly. Do not distribute the APK with your API key configured. For a public app, use a backend proxy so the API key is never shipped to clients.

## Included source
The included PDF is the user-provided **Design of Highway Pavements**, 74 pages. The app treats it as the primary study source and instructs the AI not to invent unsupported source details.
