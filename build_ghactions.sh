#!/bin/bash
# ============================================================
# Script para compilar Ultra2 Companion usando GitHub Actions
# desde Termux. Crea el workflow si no existe, lo dispara,
# espera y descarga el APK.
# ============================================================

set -e  # Detener ante cualquier error

# --- Configuración (ajusta si es necesario) ---
REPO="rgth08/ultra2-companion-final"   # Formato: usuario/repositorio
WORKFLOW_FILE="build.yml"              # Nombre del archivo del workflow
ARTIFACT_NAME="app-debug"              # Nombre del artefacto en el workflow
BRANCH="main"                          # Rama a la que hacer push
TIMEOUT_SECONDS=900                    # Tiempo máximo de espera (15 min)

# --- Funciones auxiliares ---
log() {
    echo -e "\n\033[1;32m[INFO]\033[0m $1"
}

error() {
    echo -e "\n\033[1;31m[ERROR]\033[0m $1"
    exit 1
}

check_command() {
    if ! command -v "$1" &> /dev/null; then
        error "No se encontró el comando '$1'. Instálalo con: pkg install $2"
    fi
}

# --- Verificaciones iniciales ---
log "Verificando herramientas necesarias..."
check_command git "git"
check_command gh "gh"

log "Verificando autenticación en GitHub..."
if ! gh auth status &> /dev/null; then
    error "No estás autenticado en GitHub. Ejecuta 'gh auth login' y sigue las instrucciones."
fi

# Verificar que estamos dentro de un repositorio git
if [ ! -d ".git" ]; then
    error "Este script debe ejecutarse dentro del repositorio clonado."
fi

# --- Crear workflow si no existe ---
WORKFLOW_PATH=".github/workflows/$WORKFLOW_FILE"
if [ ! -f "$WORKFLOW_PATH" ]; then
    log "No se encontró el workflow. Creando $WORKFLOW_PATH ..."
    mkdir -p .github/workflows
    cat > "$WORKFLOW_PATH" << 'EOF'
name: Build APK

on:
  push:
    branches: [ main ]
  workflow_dispatch:   # Permite disparo manual desde gh

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      - name: Build with Gradle (sin caché)
        run: ./gradlew assembleDebug --no-daemon --no-build-cache

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: app-debug
          path: app/build/outputs/apk/debug/*.apk
EOF
    log "Workflow creado correctamente."
else
    log "Workflow ya existe, se usará el actual."
fi

# --- Hacer commit y push de los cambios ---
log "Preparando cambios para push..."
git add -A
if git diff --cached --quiet; then
    log "No hay cambios para commitear."
else
    git commit -m "Auto-commit desde Termux para disparar build"
    log "Commit realizado."
fi

log "Haciendo push a la rama $BRANCH..."
git push origin "$BRANCH"

# --- Disparar workflow manualmente ---
log "Disparando workflow '$WORKFLOW_FILE' en GitHub Actions..."
gh workflow run "$WORKFLOW_FILE" --repo "$REPO"

# --- Obtener ID del último run ---
log "Obteniendo ID del último run..."
run_id=$(gh run list --repo "$REPO" --workflow="$WORKFLOW_FILE" --limit 1 --json databaseId -q '.[0].databaseId')
if [ -z "$run_id" ]; then
    error "No se pudo obtener el ID del run. Verifica que el workflow existe en el repositorio."
fi
log "Run ID: $run_id"

# --- Esperar a que termine ---
log "Esperando a que el workflow termine (máximo $TIMEOUT_SECONDS segundos)..."
if ! timeout "$TIMEOUT_SECONDS" gh run watch "$run_id" --repo "$REPO" --exit-status; then
    error "El workflow falló o excedió el tiempo de espera. Revisa los logs en GitHub."
fi

log "Workflow completado con éxito."

# --- Descargar artefacto ---
log "Descargando artefacto '$ARTIFACT_NAME'..."
DOWNLOAD_DIR="$HOME/ultra2-apk"
mkdir -p "$DOWNLOAD_DIR"
gh run download "$run_id" --repo "$REPO" --name "$ARTIFACT_NAME" --dir "$DOWNLOAD_DIR"

# --- Buscar APK y mostrar ruta ---
APK_PATH=$(find "$DOWNLOAD_DIR" -name "*.apk" | head -1)
if [ -n "$APK_PATH" ]; then
    log "✅ APK descargado exitosamente en: $APK_PATH"
    log "Puedes instalarlo con: termux-open \"$APK_PATH\""
else
    error "No se encontró ningún archivo APK en el artefacto descargado."
fi

log "Proceso completado."
