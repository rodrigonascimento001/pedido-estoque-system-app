#!/usr/bin/env bash
# Importa o dashboard e os monitores via API do Datadog (alternativa à importação pela UI).
#
# Requer: DD_API_KEY e DD_APP_KEY (Application Key, criada em Organization Settings > Application Keys).
# DD_SITE é opcional (padrão datadoghq.com; ex.: us3.datadoghq.com, datadoghq.eu).
#
# Cada execução CRIA novos objetos (não atualiza os existentes): rodar duas vezes duplica
# dashboard e monitores. Para alterar algo já criado, edite na UI ou use PUT com o id retornado.
set -euo pipefail
cd "$(dirname "$0")"

: "${DD_API_KEY:?defina DD_API_KEY}"
: "${DD_APP_KEY:?defina DD_APP_KEY}"
API="https://api.${DD_SITE:-datadoghq.com}/api/v1"

post() {
  local caminho="$1" arquivo="$2"
  echo ">> POST ${caminho}  <-  ${arquivo}"
  curl -fsS -X POST "${API}/${caminho}" \
    -H "Content-Type: application/json" \
    -H "DD-API-KEY: ${DD_API_KEY}" \
    -H "DD-APPLICATION-KEY: ${DD_APP_KEY}" \
    --data-binary "@${arquivo}"
  echo
}

post dashboard dashboard-pedidos-estoque.json
for m in monitors/*.json; do
  post monitor "$m"
done
echo "Concluído. Abra o dashboard e os monitores no Datadog para conferir."
