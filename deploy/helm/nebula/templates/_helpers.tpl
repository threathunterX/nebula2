{{- define "nebula.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "nebula.fullname" -}}
{{- printf "%s-%s" .Release.Name (include "nebula.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "nebula.labels" -}}
app.kubernetes.io/name: {{ include "nebula.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "nebula.secretName" -}}
{{- if .Values.credentials.existingSecret -}}
{{- .Values.credentials.existingSecret -}}
{{- else -}}
{{- include "nebula.fullname" . }}-credentials
{{- end -}}
{{- end -}}

{{- define "nebula.image" -}}
{{- $reg := .Values.image.registry -}}
{{- if $reg -}}{{ $reg }}/{{ end }}nebula/{{ .component }}:{{ .Values.image.tag }}
{{- end -}}

{{/*
凭据环境变量。所有组件共用一份 —— 与 compose 的 .env 一一对应,
这样两种部署形态的配置项不会分叉。
*/}}
{{- define "nebula.credentialEnv" -}}
- name: POSTGRES_USER
  valueFrom: { secretKeyRef: { name: {{ include "nebula.secretName" . }}, key: postgres-user } }
- name: POSTGRES_PASSWORD
  valueFrom: { secretKeyRef: { name: {{ include "nebula.secretName" . }}, key: postgres-password } }
- name: POSTGRES_DB
  valueFrom: { secretKeyRef: { name: {{ include "nebula.secretName" . }}, key: postgres-db } }
- name: CLICKHOUSE_USER
  valueFrom: { secretKeyRef: { name: {{ include "nebula.secretName" . }}, key: clickhouse-user } }
- name: CLICKHOUSE_PASSWORD
  valueFrom: { secretKeyRef: { name: {{ include "nebula.secretName" . }}, key: clickhouse-password } }
- name: REDIS_PASSWORD
  valueFrom: { secretKeyRef: { name: {{ include "nebula.secretName" . }}, key: redis-password } }
- name: NEBULA_HMAC_KEY
  valueFrom: { secretKeyRef: { name: {{ include "nebula.secretName" . }}, key: hmac-key } }
{{- end -}}
