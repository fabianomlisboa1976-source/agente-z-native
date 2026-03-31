# 🤖 Agente Autônomo - APK Android

Um aplicativo Android com **agente autônomo multi-agente** que funciona 24/7, integrado com LLMs gratuitos (Groq, OpenRouter), banco de dados local (Room/SQLite) e sistema de auditoria cruzada.

---

## ✨ Funcionalidades

### 🔄 Execução 24/7
- **Foreground Service** persistente com notificação
- Reinicialização automática após boot
- Reconexão automática quando a internet volta
- Otimizado para bateria

### 🤖 Sistema Multi-Agente
- **Coordenador**: Orquestra a execução entre agentes
- **Planejador**: Cria planos e organiza tarefas
- **Pesquisador**: Busca e analisa informações
- **Executor**: Executa tarefas práticas
- **Auditor**: Verifica qualidade e consistência
- **Memória**: Gerencia contexto e histórico
- **Comunicação**: Formata e envia mensagens

### 🗄️ Banco de Dados Local
- **Room Database** (SQLite)
- Mensagens persistentes
- Histórico de auditoria
- Memória de longo prazo
- Tarefas agendadas

### 🔗 Integração com LLMs (Gratuitos)
- **Groq** (recomendado) - Llama 3.1, Mixtral, Gemma
- **OpenRouter** - 100+ modelos
- **GitHub Models** - Phi-3, Mistral
- **Cloudflare Workers AI**

### 🔍 Auditoria Cruzada
- Logs detalhados de todas as ações
- Verificação de qualidade das respostas
- Rastreamento de decisões dos agentes
- Exportação de logs

### 📱 Interface
- Chatbot intuitivo
- Gerenciamento de agentes
- Configurações completas
- Visualização de auditoria

---

## 🚀 COMPILAÇÃO ONLINE (SEM COMPUTADOR)

### MÉTODO 1: GitHub Actions (RECOMENDADO - 100% Gratuito)

Este projeto já está configurado para compilar automaticamente usando **GitHub Actions**. Siga os passos:

#### Passo 1: Criar conta no GitHub
1. Acesse https://github.com
2. Clique em "Sign up" e crie uma conta gratuita
3. Verifique seu email

#### Passo 2: Criar novo repositório
1. Clique no botão "+" (New repository)
2. Nome: `AgenteAutonomo`
3. Selecione "Public" (repositório público = builds gratuitos ilimitados)
4. Clique em "Create repository"

#### Passo 3: Fazer upload dos arquivos
**Opção A - Pelo celular (navegador):**
1. No seu repositório GitHub, clique em "Add file" → "Upload files"
2. Toque em "choose your files"
3. Selecione todos os arquivos do projeto (você precisa extrair o .tar.gz primeiro)
4. Clique em "Commit changes"

**Opção B - Usando GitHub Desktop (se tiver acesso a um computador temporário):**
```bash
git clone https://github.com/SEU_USUARIO/AgenteAutonomo.git
cd AgenteAutonomo
copie todos os arquivos do projeto aqui
git add .
git commit -m "Primeira versão"
git push origin main
```

#### Passo 4: Aguardar compilação automática
1. Após fazer upload, vá na aba "Actions" do seu repositório
2. O workflow "Build Android APK" iniciará automaticamente
3. Aguarde 5-10 minutos (você pode acompanhar o progresso em tempo real)
4. Quando terminar, o APK estará disponível!

#### Passo 5: Baixar o APK
1. Vá na aba "Actions" → Clique no workflow mais recente
2. Role até "Artifacts" no final da página
3. Baixe `app-debug-apk` (versão de debug) ou `app-release-apk` (versão release)
4. O arquivo .apk estará dentro do zip baixado

#### Passo 6: Instalar no celular
1. Extraia o APK do arquivo zip
2. Transfira para seu Xiaomi Redmi 14C
3. Toque no arquivo para instalar
4. Conceda as permissões necessárias

---

## 📋 Requisitos

### Para Uso:
- **Android 8.0+** (API 26+)
- **Permissões**: Internet, Notificações, Ignorar otimização de bateria

---

## ⚙️ Configuração Inicial

### 1. Obter Chave de API (Gratuita)

#### Groq (Recomendado):
1. Acesse: https://console.groq.com
2. Crie uma conta gratuita
3. Gere uma API Key
4. Copie a chave

#### OpenRouter (Alternativa):
1. Acesse: https://openrouter.ai
2. Crie uma conta
3. Gere uma API Key

### 2. Configurar o App

1. Abra o app **Agente Autônomo**
2. Toque em **Configurações**
3. Selecione o **Provedor de API**
4. Cole sua **Chave da API**
5. Selecione o **Modelo** desejado
6. Toque em **Testar** para verificar a conexão
7. Toque em **Salvar**

### 3. Iniciar o Serviço

1. Na tela principal, toque em **Iniciar Serviço**
2. A notificação persistente aparecerá
3. O agente agora está rodando 24/7!

---

## 🎯 Como Usar

### Chat
1. Toque em **Chat** na tela principal
2. Digite sua mensagem
3. O agente coordenador analisará e distribuirá para os agentes apropriados
4. A resposta aparecerá no chat

### Gerenciar Agentes
1. Toque em **Agentes** na tela principal
2. Veja todos os agentes disponíveis
3. Ative/desative agentes conforme necessário
4. Toque em um agente para ver detalhes

### Auditoria
1. Toque em **Auditoria** na tela principal
2. Filtre logs por período (Hoje, Semana, Mês)
3. Toque em um log para ver detalhes
4. Exporte logs se necessário

---

## 🛠️ Estrutura do Projeto

```
AgenteAutonomoApp/
├── app/
│   ├── src/main/
│   │   ├── java/com/agente/autonomo/
│   │   │   ├── data/           # Data Access Objects
│   │   │   ├── database/       # Room Database
│   │   │   ├── entity/         # Entidades (Message, Agent, etc)
│   │   │   ├── service/        # Foreground Service
│   │   │   ├── agent/          # Sistema Multi-Agente
│   │   │   ├── api/            # Integração LLM
│   │   │   ├── ui/             # Interface do Usuário
│   │   │   └── utils/          # Utilitários
│   │   ├── res/                # Recursos (layouts, strings, etc)
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── .github/workflows/           # GitHub Actions (compilação automática)
├── build.gradle.kts
└── settings.gradle.kts
```

---

## 🔐 Segurança

- API Keys são armazenadas localmente no dispositivo
- Banco de dados local criptografado
- Auditoria de todas as ações
- Sem envio de dados para servidores externos (exceto APIs de LLM)

---

## 📝 Notas Importantes

### Xiaomi Redmi 14C
Para garantir funcionamento 24/7 no seu Xiaomi:

1. **Configurações → Apps → Agente Autônomo**
2. **Bateria → Sem restrições**
3. **Permissões → Permitir todas**
4. **Bloqueio de limpeza → Ativar**

### Otimização de Bateria
O app solicitará permissão para ignorar otimização de bateria. **Aceite** para garantir funcionamento contínuo.

### Consumo de Dados
O app consome dados apenas quando:
- Envia mensagens para APIs de LLM
- Sincroniza dados (se habilitado)

---

## 🐛 Solução de Problemas

### Serviço não inicia:
- Verifique se a otimização de bateria está desabilitada
- Conceda todas as permissões solicitadas
- Reinicie o celular

### API retorna erro:
- Verifique se a chave de API está correta
- Teste a conexão nas configurações
- Tente outro provedor de API

### App fecha sozinho:
- Adicione o app à lista de protegidos (Xiaomi)
- Desabilite otimização de bateria
- Verifique se há espaço livre no celular

---

## 💰 CUSTO: R$ 0,00

Todas as ferramentas usadas são **gratuitas**:
- ✅ GitHub (repositório público = builds gratuitos)
- ✅ GitHub Actions (CI/CD gratuito para projetos públicos)
- ✅ APIs de LLM (nível gratuito generoso)
- ✅ Banco de dados local (gratuito)

---

## 📞 Suporte

Para dúvidas ou problemas:
1. Verifique os logs em **Auditoria**
2. Consulte esta documentação
3. Verifique a configuração da API

---

## 🔄 Atualizações Automáticas

Com o GitHub Actions configurado:
1. Sempre que você fizer alterações no código e fazer push
2. O APK será recompilado automaticamente
3. A nova versão estará disponível na aba Actions

---

**Desenvolvido com ❤️ para uso pessoal**

*Versão 1.0.0 - 2024*
