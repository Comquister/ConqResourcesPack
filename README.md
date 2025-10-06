# ConqResourcesPack

O **ConqResourcesPack** é um plugin para **Spigot/Paper** que permite gerenciar e distribuir *resource packs* personalizados de forma automática e unificada no servidor.

Ele busca *resource packs* diretamente de fontes externas como **Modrinth**, **CurseForge** e URLs diretas, faz o **merge** dos arquivos, aplica *overlays* customizados do servidor e distribui automaticamente o pack certo para cada jogador de acordo com sua versão do Minecraft (compatível com **ViaVersion**).

---

## ✨ Funcionalidades

* 🔗 **Integração automática** com **Modrinth** e **CurseForge** (via API).
* 🌍 Suporte a URLs diretas de arquivos `.zip`.
* ⚡ Criação e cache automático de *resource packs* mesclados.
* 🖌️ Possibilidade de aplicar um *overlay* customizado (ex: logo do servidor, sons personalizados, traduções).
* 📡 Servidor HTTP interno para servir os *resource packs*.
* 🔄 Compatibilidade com **ViaVersion**, enviando o pack correto para a versão do jogador.
* 📦 Merge inteligente de arquivos `.json` (sons, línguas, modelos, blockstates).
* ⚙️ Comandos administrativos para gerenciamento.

---

## 📦 Comandos

* `/conqresourcepack clear` → Limpa o cache e apaga os packs gerados.
* `/conqresourcepack status` → Mostra o status das fontes e cache.
* `/conqresourcepack reload` → Recarrega a configuração e as fontes.
* `/conqresourcepack zip` → Reenvia o *resource pack* atual para todos os jogadores online.

---

## ⚙️ Configuração

O arquivo `config.yml` permite personalizar:

* **resourcepacks**: lista de fontes de packs (links do Modrinth, CurseForge ou `.zip`).
* **custom-overlay-folder**: pasta com arquivos que serão aplicados sobre o pack final.
* **http-port**: porta usada pelo servidor HTTP interno.
* **server-ip**: IP do servidor (usado para gerar o link do pack).
* **curseforge-api-key**: chave da API do CurseForge (obrigatória para integração).

Exemplo de `config.yml`:

```yaml
# ============================
#  ConqResourcesPack - Config
# ============================

# Lista de resource packs que o servidor vai usar.
# Você pode usar links do Modrinth, CurseForge ou URLs diretas (.zip).
# A ordem importa: os últimos da lista vão sobrescrever arquivos dos primeiros.
#
# Exemplos:
#  - Modrinth: https://modrinth.com/resourcepack/unique-dark
#  - CurseForge: https://www.curseforge.com/minecraft/texture-packs/fresh-animations
#  - Link direto: https://meusite.com/packs/personalizado.zip
#
# Se quiser desativar um pack sem apagar, basta comentar a linha com "#".
resourcepacks:
  - https://modrinth.com/resourcepack/unique-dark
  - https://modrinth.com/resourcepack/icons
  - https://modrinth.com/resourcepack/tras-fresh-player
  #- https://www.curseforge.com/minecraft/texture-packs/fresh-animations
  #- "https://meusite.com/packs/personalizado.zip"

# Pasta de overlay customizado
# Todos os arquivos dentro dessa pasta serão adicionados por cima
# dos packs mesclados. Útil para ajustes específicos do servidor
# (ex: logo customizada, sons, ícones de GUI).
custom-overlay-folder: "server_pack"

# Porta usada pelo servidor HTTP interno para hospedar os resource packs.
# Certifique-se de que essa porta esteja liberada no firewall/host.
http-port: 8080

# IP ou domínio público do servidor, usado para gerar os links dos resource packs.
# Exemplo: "meu.dominio.com" ou "127.0.0.1" (para testes locais).
server-ip: "meu.dominio.com"

# Chave de API do CurseForge (opcional).
# Necessária apenas se você quiser usar packs do CurseForge.
# Para obter, crie uma chave em: https://console.curseforge.com/
curseforge-api-key: "MINHA_CHAVE_API"
```

---

## 🚀 Como funciona

1. O plugin baixa todos os *resource packs* configurados.
2. Mescla os packs em um único `.zip`, unificando arquivos duplicados.
3. Aplica os arquivos extras do *overlay customizado*.
4. Gera um hash SHA-1 do pack final e disponibiliza via HTTP.
5. No login, o jogador recebe automaticamente o pack correto para a sua versão do Minecraft.

---

## 📌 Requisitos

* Servidor **Spigot/Paper 1.16+**
* (Opcional) **ViaVersion** para suportar múltiplas versões de jogadores.
* (Opcional) **API Key do CurseForge** para usar recursos do CurseForge.

---

## 📄 Licença

Este plugin é distribuído sob a licença **MIT**.

---

Quer que eu já adapte esse `README.md` para ficar pronto com **badges** (ex: versão do plugin, build status, compatibilidade com Minecraft), ou prefere manter mais simples?
