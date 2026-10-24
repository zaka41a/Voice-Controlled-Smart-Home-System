'use strict';

const $ = (selector) => document.querySelector(selector);
const emptyResultMarkup = $('#command-result').innerHTML;
const icon = (name) => `<svg class="icon" aria-hidden="true"><use href="#i-${name}"/></svg>`;
const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (character) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character]));
const stateNames = { ON: 'Allumé', OFF: 'Éteint', UNKNOWN: 'État inconnu' };
const outcomeNames = { CONFIRMED: 'Action confirmée', REJECTED: 'Commande rejetée', FAILED: 'Exécution échouée', UNCONFIRMED: 'Exécution non confirmée' };
const modeNames = { CONFIRMED: 'Simulation', UNCONFIRMED: 'Sans confirmation', OFFLINE: 'Indisponible' };
const pages = {
  overview: ['Vue d’ensemble', 'Votre maison, en un regard.', 'Un espace pour configurer, piloter et comprendre votre système.'],
  devices: ['Appareils', 'Chaque appareil a sa place.', 'Configurez les appareils et explorez leurs comportements simulés.'],
  commands: ['Commandes', 'Les bons mots, la bonne action.', 'Associez chaque commande reconnue à un appareil et à une action.'],
  activity: ['Activité', 'Comprendre chaque réponse.', 'Observez les commandes acceptées, rejetées et non confirmées.'],
};
let data = { devices: [], mappings: [], events: [], settings: { minimumConfidence: .8 } };
let connected = false;
let commandPending = false;
let refreshPending = false;
let pendingDeletion = null;
let toastTimer;

async function api(path, options = {}) {
  let response;
  try {
    response = await fetch(`/api${path}`, {
      ...options,
      headers: { Accept: 'application/json', ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...options.headers },
    });
  } catch {
    throw new Error('Impossible de joindre le backend. Vérifiez que l’application est démarrée.');
  }
  if (response.status === 204) return null;
  let body;
  try { body = await response.json(); }
  catch { throw new Error('Le serveur a renvoyé une réponse inattendue.'); }
  if (!response.ok) throw new Error(body.message || 'La demande n’a pas pu être traitée.');
  return body;
}

function toast(message, error = false) {
  clearTimeout(toastTimer);
  $('#toast').textContent = message;
  $('#toast').classList.toggle('error', error);
  $('#toast').hidden = false;
  toastTimer = setTimeout(() => { $('#toast').hidden = true; }, 4500);
}

function setConnection(value) {
  connected = value;
  $('#connection-status').classList.toggle('disconnected', !value);
  $('#connection-status').innerHTML = `<span class="small-dot"></span>${value ? 'Backend connecté' : 'Backend indisponible'}`;
  $('#connection-error').hidden = value;
  updateCommandButtons();
}

function updateCommandButtons() {
  $('#send-command').disabled = commandPending || !connected;
  $('#send-command').innerHTML = `${commandPending ? 'Traitement en cours…' : 'Envoyer la commande'}${icon('arrow')}`;
  document.querySelectorAll('[data-command]').forEach((button) => {
    button.disabled = !button.dataset.command || !connected || commandPending;
  });
}

async function refresh({ quiet = false } = {}) {
  if (refreshPending) return;
  refreshPending = true;
  try {
    data = await api('/dashboard');
    render();
    setConnection(true);
  } catch (error) {
    setConnection(false);
    if (!quiet) toast(error.message, true);
  } finally { refreshPending = false; }
}

function actionMapping(deviceId, action) {
  return data.mappings.find((mapping) => mapping.deviceId === deviceId && mapping.action === action);
}

function deviceCard(device) {
  const on = actionMapping(device.id, 'ON');
  const off = actionMapping(device.id, 'OFF');
  const state = device.reportedState;
  const actionButton = (action, label, mapping) => `<button class="button ${state === action ? 'current' : ''}" data-command="${escapeHtml(mapping?.commandId || '')}" ${mapping ? '' : 'disabled'} title="${mapping ? `Tester ${escapeHtml(mapping.commandId)} avec 100 % de confiance` : 'Configurez d’abord une association pour cette action'}">${label}</button>`;
  return `<article class="device-card ${state === 'ON' ? 'on' : ''}">
    <div class="device-card-top"><span class="device-icon">${icon('bulb')}</span><span class="device-state ${state.toLowerCase()}"><span class="small-dot"></span>${stateNames[state]}</span></div>
    <h3>${escapeHtml(device.name)}</h3><div class="device-location">${escapeHtml(device.room)} <span>·</span> <span class="pill pill-neutral">${modeNames[device.simulationMode]}</span></div>
    <div class="device-card-bottom"><div class="device-actions">${actionButton('ON', 'Allumer', on)}${actionButton('OFF', 'Éteindre', off)}</div><div><button class="icon-button" data-action="edit-device" data-id="${escapeHtml(device.id)}" aria-label="Configurer ${escapeHtml(device.name)}">${icon('edit')}</button><button class="icon-button" data-action="delete-device" data-id="${escapeHtml(device.id)}" aria-label="Supprimer ${escapeHtml(device.name)}">${icon('close')}</button></div></div>
    <div class="requested-state">Dernier état demandé : ${device.requestedState === 'UNKNOWN' ? 'aucun' : stateNames[device.requestedState].toLowerCase()}</div>
  </article>`;
}

function eventList(events) {
  if (!events.length) return `<div class="empty-state">${icon('activity')}<strong>Le journal commence avec vous.</strong><p>Envoyez une première commande pour observer son parcours.</p></div>`;
  return `<div class="activity-list">${events.map((event) => {
    const time = new Date(event.timestamp);
    return `<div class="activity-row"><span class="event-icon ${event.outcome.toLowerCase()}">${icon(event.outcome === 'CONFIRMED' ? 'check' : event.outcome === 'UNCONFIRMED' ? 'clock' : 'close')}</span><div class="event-description"><strong>${escapeHtml(event.commandId || '(commande vide)')}</strong><p>${escapeHtml(event.message)}</p></div><div class="event-outcome">${outcomeNames[event.outcome]}<time datetime="${escapeHtml(event.timestamp)}" title="${escapeHtml(time.toLocaleString('fr-FR'))}">${time.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit', second: '2-digit' })}</time></div></div>`;
  }).join('')}</div>`;
}

function renderResult(event) {
  if (!event) {
    if ($('#command-result').dataset.eventId) {
      delete $('#command-result').dataset.eventId;
      $('#command-result').innerHTML = emptyResultMarkup;
    }
    return;
  }
  if ($('#command-result').dataset.eventId === event.id) return;
  $('#command-result').dataset.eventId = event.id;
  const device = data.devices.find((item) => item.id === event.deviceId);
  $('#command-result').innerHTML = `<span class="result-badge ${event.outcome.toLowerCase()}">${icon(event.outcome === 'CONFIRMED' ? 'check' : event.outcome === 'UNCONFIRMED' ? 'clock' : 'close')}</span><span class="result-command">${escapeHtml(event.commandId || '(commande vide)')}</span><h3>${outcomeNames[event.outcome]}</h3><p>${escapeHtml(event.message)}</p><div class="result-meta"><div>État disponible<strong>${stateNames[event.reportedState]}</strong></div><div>Traitement serveur<strong>${event.durationMs.toLocaleString('fr-FR', { maximumFractionDigits: 2 })} ms</strong></div></div>${device ? `<p style="margin-top:18px">${escapeHtml(device.name)} · ${escapeHtml(device.room)}</p>` : ''}`;
}

function render() {
  $('#stat-devices').textContent = data.devices.length;
  $('#nav-device-count').textContent = data.devices.length;
  $('#stat-on').textContent = data.devices.filter((device) => device.reportedState === 'ON').length;
  $('#stat-mappings').textContent = data.mappings.length;
  $('#confidence-threshold').textContent = `Seuil de démonstration : ${Math.round(data.settings.minimumConfidence * 100)} %`;
  const addCard = `<button class="device-add" data-action="add-device">${icon('plus')}Ajouter un appareil</button>`;
  $('#overview-devices').innerHTML = data.devices.slice(0, 3).map(deviceCard).join('') + addCard;
  $('#all-devices').innerHTML = data.devices.map(deviceCard).join('') + addCard;
  $('#overview-activity').innerHTML = eventList(data.events.slice(0, 4));
  $('#all-activity').innerHTML = eventList(data.events);
  const selectedCommand = $('#test-command').value;
  $('#test-command').innerHTML = data.mappings.map((mapping) => {
    const device = data.devices.find((item) => item.id === mapping.deviceId);
    return `<option value="${escapeHtml(mapping.commandId)}">${escapeHtml(mapping.commandId)} — ${escapeHtml(device?.name || mapping.deviceId)}</option>`;
  }).join('') + '<option value="__UNKNOWN_COMMAND__">Commande inconnue — test de rejet</option>';
  if ([...$('#test-command').options].some((option) => option.value === selectedCommand)) $('#test-command').value = selectedCommand;
  else if (data.mappings.some((mapping) => mapping.commandId === 'LIGHT_ON')) $('#test-command').value = 'LIGHT_ON';
  $('#mappings-table').innerHTML = data.mappings.length ? `<table><thead><tr><th>COMMANDE RECONNUE</th><th>APPAREIL CIBLE</th><th>ACTION</th><th><span class="sr-only">Configuration</span></th></tr></thead><tbody>${data.mappings.map((mapping) => `<tr><td><code>${escapeHtml(mapping.commandId)}</code></td><td>${escapeHtml(data.devices.find((device) => device.id === mapping.deviceId)?.name || mapping.deviceId)}</td><td>${mapping.action === 'ON' ? 'Allumer' : 'Éteindre'} <span class="pill pill-neutral">${mapping.action}</span></td><td><button class="text-link" data-action="edit-mapping" data-id="${escapeHtml(mapping.commandId)}">Modifier</button><button class="text-link danger" data-action="delete-mapping" data-id="${escapeHtml(mapping.commandId)}">Supprimer</button></td></tr>`).join('')}</tbody></table>` : '<div class="empty-state"><strong>Aucune association pour le moment.</strong><p>Créez une association pour relier une commande à un appareil.</p></div>';
  renderResult(data.events[0]);
  updateCommandButtons();
}

function navigate(view) {
  const selected = pages[view] ? view : 'overview';
  document.querySelectorAll('.view').forEach((section) => { section.hidden = section.id !== `view-${selected}`; });
  document.querySelectorAll('.nav-item').forEach((button) => {
    const active = button.dataset.view === selected;
    button.classList.toggle('active', active);
    if (active) button.setAttribute('aria-current', 'page');
    else button.removeAttribute('aria-current');
  });
  const [name, title, subtitle] = pages[selected];
  $('#breadcrumb-current').textContent = name;
  $('#page-title').textContent = title;
  $('#page-subtitle').textContent = subtitle;
  document.title = `${name} — VoiceHome`;
}

function showFormError(id, message) {
  $(id).textContent = message;
  $(id).hidden = !message;
}

function showDevice(id) {
  const device = data.devices.find((item) => item.id === id);
  $('#device-form').reset();
  $('#device-form').dataset.editing = device ? 'true' : 'false';
  $('#device-dialog-title').textContent = device ? 'Configurer l’appareil' : 'Ajouter un appareil';
  $('#device-id').readOnly = Boolean(device);
  if (device) {
    $('#device-id').value = device.id;
    $('#device-name').value = device.name;
    $('#device-room').value = device.room;
    $('#device-mode').value = device.simulationMode;
  }
  showFormError('#device-form-error', '');
  $('#device-dialog').showModal();
}

function showMapping(id) {
  if (!data.devices.length) { toast('Ajoutez d’abord un appareil.'); showDevice(); return; }
  const mapping = data.mappings.find((item) => item.commandId === id);
  $('#mapping-form').reset();
  $('#mapping-form').dataset.editing = mapping ? 'true' : 'false';
  $('#mapping-dialog-title').textContent = mapping ? 'Modifier l’association' : 'Nouvelle association';
  $('#mapping-id').readOnly = Boolean(mapping);
  $('#mapping-device').innerHTML = data.devices.map((device) => `<option value="${escapeHtml(device.id)}">${escapeHtml(device.name)} — ${escapeHtml(device.room)}</option>`).join('');
  if (mapping) {
    $('#mapping-id').value = mapping.commandId;
    $('#mapping-device').value = mapping.deviceId;
    $('#mapping-action').value = mapping.action;
  }
  showFormError('#mapping-form-error', '');
  $('#mapping-dialog').showModal();
}

function showDeletion(type, id) {
  pendingDeletion = { type, id };
  $('#confirm-title').textContent = type === 'devices' ? 'Supprimer cet appareil ?' : 'Supprimer cette association ?';
  $('#confirm-description').textContent = type === 'devices'
    ? 'Les associations utilisant cet appareil doivent être supprimées auparavant. La configuration de l’appareil sera retirée.'
    : `La commande ${id} ne pourra plus piloter d’appareil tant qu’une nouvelle association n’aura pas été définie.`;
  showFormError('#confirm-error', '');
  $('#confirm-dialog').showModal();
}

async function sendCommand(commandId, confidence) {
  if (commandPending) return;
  commandPending = true;
  updateCommandButtons();
  try {
    const result = await api('/commands', { method: 'POST', body: JSON.stringify({ commandId, confidence }) });
    await refresh({ quiet: true });
    renderResult(result);
    toast(outcomeNames[result.outcome], result.outcome === 'FAILED');
  } catch (error) { toast(error.message, true); }
  finally { commandPending = false; updateCommandButtons(); }
}

document.addEventListener('click', (event) => {
  const button = event.target.closest('button');
  if (!button || button.disabled) return;
  if (button.dataset.view) { location.hash = button.dataset.view; navigate(button.dataset.view); }
  if (button.dataset.close) document.getElementById(button.dataset.close).close();
  if (button.dataset.command) void sendCommand(button.dataset.command, 1);
  const id = button.dataset.id;
  switch (button.dataset.action) {
    case 'add-device': showDevice(); break;
    case 'edit-device': showDevice(id); break;
    case 'delete-device': showDeletion('devices', id); break;
    case 'add-mapping': showMapping(); break;
    case 'edit-mapping': showMapping(id); break;
    case 'delete-mapping': showDeletion('mappings', id); break;
    case 'refresh': void refresh(); break;
  }
});

$('#confidence').addEventListener('input', () => { $('#confidence-value').value = `${$('#confidence').value} %`; });
$('#command-form').addEventListener('submit', (event) => {
  event.preventDefault();
  void sendCommand($('#test-command').value, Number($('#confidence').value) / 100);
});

$('#device-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const button = event.submitter;
  button.disabled = true;
  try {
    const id = $('#device-id').value.trim();
    if ($('#device-form').dataset.editing !== 'true' && data.devices.some((device) => device.id === id)) throw new Error('Cet identifiant existe déjà. Choisissez-en un autre.');
    await api(`/devices/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify({ name: $('#device-name').value.trim(), room: $('#device-room').value.trim(), simulationMode: $('#device-mode').value }) });
    $('#device-dialog').close();
    await refresh({ quiet: true });
    toast('Appareil enregistré.');
  } catch (error) { showFormError('#device-form-error', error.message); }
  finally { button.disabled = false; }
});

$('#mapping-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const button = event.submitter;
  button.disabled = true;
  try {
    const id = $('#mapping-id').value.trim();
    if ($('#mapping-form').dataset.editing !== 'true' && data.mappings.some((mapping) => mapping.commandId === id)) throw new Error('Cette commande possède déjà une association. Utilisez « Modifier ».');
    await api(`/mappings/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify({ deviceId: $('#mapping-device').value, action: $('#mapping-action').value }) });
    $('#mapping-dialog').close();
    await refresh({ quiet: true });
    toast('Association enregistrée.');
  } catch (error) { showFormError('#mapping-form-error', error.message); }
  finally { button.disabled = false; }
});

$('#confirm-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const button = event.submitter;
  button.disabled = true;
  try {
    await api(`/${pendingDeletion.type}/${encodeURIComponent(pendingDeletion.id)}`, { method: 'DELETE' });
    $('#confirm-dialog').close();
    await refresh({ quiet: true });
    toast('Configuration supprimée.');
  } catch (error) { showFormError('#confirm-error', error.message); }
  finally { button.disabled = false; }
});

$('#today').textContent = new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' }).format(new Date());
window.addEventListener('hashchange', () => navigate(location.hash.slice(1)));
document.addEventListener('visibilitychange', () => { if (!document.hidden) void refresh({ quiet: true }); });
navigate(location.hash.slice(1));
updateCommandButtons();
void refresh({ quiet: true });
setInterval(() => { if (!document.hidden && !document.querySelector('dialog[open]')) void refresh({ quiet: true }); }, 5000);
