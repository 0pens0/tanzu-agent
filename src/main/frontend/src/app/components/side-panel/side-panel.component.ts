import { Component, computed, effect, inject, input, signal, OnInit } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { ChatService, GooseConfig, SkillInfo, McpServerInfo } from '../../services/chat.service';
import { McpOAuthService } from '../../services/mcp-oauth.service';
import { MemoryService } from '../../services/memory.service';
import { MemoryPanelComponent } from '../memory-panel/memory-panel.component';
import { SecurityDiagramDialogComponent } from '../security-diagram-dialog/security-diagram-dialog.component';

export type NavSection = 'skills' | 'mcp' | 'memory';

@Component({
  selector: 'app-side-panel',
  standalone: true,
  imports: [
    MatIconModule,
    MatButtonModule,
    MatTooltipModule,
    MatProgressSpinnerModule,
    MatDialogModule,
    MemoryPanelComponent
  ],
  templateUrl: './side-panel.component.html',
  styleUrl: './side-panel.component.scss'
})
export class SidePanelComponent implements OnInit {
  protected activeSection = signal<NavSection>('skills');
  protected detailOpen    = signal(true);

  // Config state
  private readonly _config  = signal<GooseConfig | null>(null);
  private readonly _loading = signal(true);
  private readonly _error   = signal<string | null>(null);

  readonly config        = this._config.asReadonly();
  readonly loading       = this._loading.asReadonly();
  readonly skills        = computed(() => this._config()?.skills ?? []);
  readonly mcpServers    = computed(() => this._config()?.mcpServers ?? []);
  readonly isBrokerMode  = computed(() => this.oauthService.isBrokerMode);

  // Memory
  readonly memoryAvailable = computed(() => this.memoryService.memoryAvailable());
  readonly contextLoaded   = computed(() => this.memoryService.contextLoaded());

  private readonly dialog = inject(MatDialog);

  constructor(
    private chatService: ChatService,
    private oauthService: McpOAuthService,
    private memoryService: MemoryService
  ) {
    // Refresh memory data when Memory section becomes active
    effect(() => {
      if (this.activeSection() === 'memory' && this.detailOpen()) {
        this.memoryService.loadFacts();
        this.memoryService.loadConversations();
      }
    });
  }

  ngOnInit(): void {
    this.loadConfig();
    this.oauthService.checkBrokerConfig();
  }

  private async loadConfig(): Promise<void> {
    this._loading.set(true);
    this._error.set(null);
    try {
      const config = await this.chatService.getConfig();
      this._config.set(config);
    } catch {
      this._error.set('Failed to load configuration');
    } finally {
      this._loading.set(false);
    }
  }

  protected selectSection(section: NavSection): void {
    if (this.activeSection() === section) {
      this.detailOpen.update(v => !v);
    } else {
      this.activeSection.set(section);
      this.detailOpen.set(true);
    }
  }

  protected sectionTitle(section: NavSection): string {
    const titles: Record<NavSection, string> = {
      skills: 'Skills',
      mcp:    'MCP Servers',
      memory: 'Memory'
    };
    return titles[section];
  }

  protected sectionIcon(section: NavSection): string {
    const icons: Record<NavSection, string> = {
      skills: 'school',
      mcp:    'dns',
      memory: 'psychology'
    };
    return icons[section];
  }

  // ── Skill helpers ─────────────────────────────────────────────────────

  protected getSkillIcon(skill: SkillInfo): string {
    switch (skill.source) {
      case 'git':  return 'cloud_download';
      case 'file': return 'folder';
      default:     return 'description';
    }
  }

  protected getSkillSourceLabel(skill: SkillInfo): string {
    switch (skill.source) {
      case 'git':  return 'Git';
      case 'file': return 'File';
      default:     return 'Inline';
    }
  }

  protected getSkillTooltip(skill: SkillInfo): string {
    if (skill.description) return skill.description;
    if (skill.repository) {
      return `${skill.repository}${skill.branch ? ` (${skill.branch})` : ''}${skill.path ? ` → ${skill.path}` : ''}`;
    }
    return skill.name;
  }

  protected getSkillRepoUrl(skill: SkillInfo): string | null {
    if (skill.source !== 'git' || !skill.repository) return null;
    const base   = skill.repository.replace(/\.git$/, '');
    const branch = skill.branch || 'main';
    return skill.path ? `${base}/tree/${branch}/${skill.path}` : base;
  }

  // ── MCP Server helpers ────────────────────────────────────────────────

  protected getMcpServerIcon(server: McpServerInfo): string {
    return server.type === 'streamable_http' ? 'cloud' : 'terminal';
  }

  protected getMcpServerTypeLabel(server: McpServerInfo): string {
    return server.type === 'streamable_http' ? 'HTTP' : 'Stdio';
  }

  protected getMcpServerTooltip(server: McpServerInfo): string {
    if (server.url)     return server.url;
    if (server.command) return `${server.command} ${server.args?.join(' ') ?? ''}`.trim();
    return server.name;
  }

  protected getMcpServerDetail(server: McpServerInfo): string {
    if (server.url)     return server.url;
    if (server.command) return server.command;
    return '';
  }

  protected requiresAuth(server: McpServerInfo): boolean {
    return server.requiresAuth === true;
  }

  protected openSecurityDiagram(): void {
    this.dialog.open(SecurityDiagramDialogComponent, {
      width: '880px',
      maxWidth: '95vw',
    });
  }

  protected openBrokerGrants(): void {
    this.oauthService.openBrokerGrants();
  }
}
